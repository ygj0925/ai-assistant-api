/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.continew.admin.assistant.run;

import cn.dev33.satoken.annotation.SaIgnore;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess.Witness;
import top.continew.admin.assistant.run.AssistantRuns.AccountUsage;
import top.continew.admin.assistant.run.AssistantRuns.Request;
import top.continew.admin.assistant.run.AssistantRuns.Snapshot;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Consumer endpoints for durable runs and resumable event delivery.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1")
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantRunController {

    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private static final String LAST_EVENT_ID = "Last-Event-ID";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final long MAX_SEQUENCE = 9_007_199_254_740_991L;
    private static final Pattern SEQUENCE = Pattern.compile("0|[1-9][0-9]{0,15}");
    private static final int STREAM_THREADS = 16;
    private static final long STREAM_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long STREAM_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(16);
    private static final long POLL_MILLIS = 100;
    private static final MediaType SSE =
        new MediaType("text", "event-stream", StandardCharsets.UTF_8);

    private final AssistantAccounts accounts;
    private final AssistantRuns runs;
    private final ThreadPoolExecutor streams =
        new ThreadPoolExecutor(STREAM_THREADS, STREAM_THREADS,
            0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            new CustomizableThreadFactory("assistant-run-stream-"));

    /**
     * Connects consumer authentication and durable runs.
     *
     * @param accounts consumer authentication
     * @param runs durable run service
     */
    public AssistantRunController(AssistantAccounts accounts, AssistantRuns runs) {
        this.accounts = accounts;
        this.runs = runs;
    }

    /**
     * Enqueues a run or returns the result of an idempotent request.
     *
     * @param authorization bearer header
     * @param workspace requested workspace or null
     * @param conversationId conversation identifier
     * @param idempotencyKey caller-provided request key
     * @param request generation input
     * @return accepted run snapshot
     */
    @PostMapping("/conversations/{conversationId}/runs")
    public ResponseEntity<Snapshot> start(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("conversationId") String conversationId,
        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
        @RequestBody Request request) {
        var actor = accounts.authenticate(authorization, workspace);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
            .body(runs.enqueue(actor, conversationId, idempotencyKey, request));
    }

    /**
     * Reads an authorized run snapshot.
     *
     * @param authorization bearer header
     * @param workspace requested workspace or null
     * @param runId run identifier
     * @return run snapshot
     */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<Snapshot> get(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("runId") String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(runs.get(accounts.authenticate(authorization, workspace), runId));
    }

    /**
     * Reads the authenticated consumer's usage and allowance.
     *
     * @param authorization bearer header
     * @param workspace requested workspace or null
     * @return current usage and remaining allowance
     */
    @GetMapping("/usage")
    public ResponseEntity<AccountUsage> usage(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(runs.usage(accounts.authenticate(authorization, workspace)));
    }

    /**
     * Requests cancellation and returns the committed run state.
     *
     * @param authorization bearer header
     * @param workspace requested workspace or null
     * @param runId run identifier
     * @return run snapshot
     */
    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Snapshot> cancel(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("runId") String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(runs.cancel(accounts.authenticate(authorization, workspace), runId));
    }

    /**
     * Replays persisted events and polls for new events for a bounded interval.
     *
     * @param authorization bearer header
     * @param workspace requested workspace or null
     * @param runId run identifier
     * @param lastEventId optional last applied sequence
     * @param response servlet response, whose headers are set before asynchronous writes
     * @return UTF-8 server-sent event response
     */
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE_HEADER, required = false) String workspace,
        @PathVariable("runId") String runId,
        @RequestHeader(value = LAST_EVENT_ID, required = false) String lastEventId,
        HttpServletResponse response) {
        var actor = accounts.authenticate(authorization, workspace);
        long cursor = parseCursor(lastEventId);
        Witness proof = runs.events(actor, runId, cursor).proof();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean active = new AtomicBoolean(true);
        emitter.onCompletion(() -> active.set(false));
        emitter.onError(ignored -> active.set(false));
        emitter.onTimeout(() -> active.set(false));

        String streamWorkspace = actor.user().workspaceId();
        try {
            streams.execute(() -> stream(emitter, active, authorization, streamWorkspace, runId,
                cursor, proof));
        } catch (RejectedExecutionException e) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Event streams are busy. Reconnect from the last received event.");
        }
        response.setContentType(SSE.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Accel-Buffering", "no");
        return emitter;
    }

    /**
     * Returns the consumer API error envelope.
     *
     * @param exception consumer API error
     * @return HTTP error
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handleApiError(AssistantApiException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    /**
     * Returns a validation error for malformed generation input.
     *
     * @return HTTP validation error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid run request.");
    }

    /**
     * Maps data failures without returning database details.
     *
     * @return HTTP service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleStorageError() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The run is unavailable.");
    }

    /**
     * Stops the event executor when the application closes.
     */
    @PreDestroy
    public void closeStreams() {
        streams.shutdownNow();
    }

    private void stream(SseEmitter emitter, AtomicBoolean active, String authorization,
        String workspace, String runId, long cursor, Witness proof) {
        long deadline = System.nanoTime() + STREAM_LIFETIME_NANOS;
        long heartbeatAt = System.nanoTime();
        try {
            while (active.get() && System.nanoTime() - deadline < 0L) {
                runs.verifyEventRead(proof);
                var actor = accounts.authenticate(authorization, workspace);
                var batch = runs.events(actor, runId, cursor);
                for (var event : batch.items()) {
                    if (!active.get()) {
                        return;
                    }
                    runs.verifyEventRead(proof);
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence()))
                        .name(event.type()).data(event, MediaType.APPLICATION_JSON));
                    cursor = event.sequence();
                }
                if (batch.terminal()) {
                    break;
                }
                if (batch.items().isEmpty()) {
                    long now = System.nanoTime();
                    if (now - heartbeatAt >= 0L) {
                        runs.verifyEventRead(proof);
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        heartbeatAt = now + HEARTBEAT_NANOS;
                    }
                    Thread.sleep(POLL_MILLIS);
                }
            }
        } catch (IOException | IllegalStateException disconnected) {
            // The client can reconnect using the last frame it consumed.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (AssistantApiException | DataAccessException unavailable) {
            // The next request receives the new access, cursor or storage error.
        } finally {
            emitter.complete();
        }
    }

    private static long parseCursor(String lastEventId) {
        if (lastEventId == null) {
            return 0L;
        }
        if (SEQUENCE.matcher(lastEventId).matches()) {
            long sequence = Long.parseLong(lastEventId);
            if (sequence <= MAX_SEQUENCE) {
                return sequence;
            }
        }
        throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Last-Event-ID must be a non-negative safe integer.");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON).body(new ApiError(code, message));
    }
}
