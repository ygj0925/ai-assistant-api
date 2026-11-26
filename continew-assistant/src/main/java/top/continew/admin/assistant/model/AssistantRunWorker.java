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

package top.continew.admin.assistant.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.memory.AssistantGenerationMemory;
import top.continew.admin.assistant.observability.AssistantOperations;
import top.continew.admin.assistant.observability.AssistantOperations.Generation;
import top.continew.admin.assistant.observability.AssistantOperations.Outcome;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.run.AssistantRuns;

import jakarta.annotation.PreDestroy;
import java.io.Serial;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Claims durable work without holding the application scheduling thread during generation.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantRunWorker {

    private final AssistantRuns runs;
    private final AssistantModels models;
    private final AssistantOperations operations;
    private final AssistantDeletionJournal deletionJournal;
    private final AssistantGenerationMemory generationMemory;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "assistant-text-worker");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Creates a worker with one generation slot per application instance.
     *
     * @param runs durable run service
     * @param models text generation gateway
     * @param operations sanitized operational logging
     * @param deletionJournal independent privacy replay gate
     * @param generationMemory privately authorized materials for synthetic generation
     */
    public AssistantRunWorker(AssistantRuns runs, AssistantModels models,
        AssistantOperations operations, AssistantDeletionJournal deletionJournal,
        AssistantGenerationMemory generationMemory) {
        this.runs = runs;
        this.models = models;
        this.operations = operations;
        this.deletionJournal = deletionJournal;
        this.generationMemory = generationMemory;
    }

    /**
     * Schedules a queue poll when the generation slot is free.
     */
    @Scheduled(fixedDelay = 200)
    public void tick() {
        if (deletionJournal.dispatchPaused() || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::claim);
        } catch (RejectedExecutionException ignored) {
            busy.set(false);
        }
    }

    /**
     * Interrupts generation as the application shuts down.
     */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private void claim() {
        if (deletionJournal.dispatchPaused()) {
            busy.set(false);
            return;
        }
        boolean retrieved = false;
        try {
            var candidate = runs.claimNext();
            retrieved = true;
            candidate.ifPresent(this::process);
        } catch (RuntimeException failure) {
            if (retrieved) {
                log.warn("event=assistant_worker_transition_failed failure_type={}",
                    failure.getClass().getSimpleName());
            }
        } finally {
            operations.queuePoll(retrieved);
            busy.set(false);
        }
    }

    private void process(AssistantRuns.Claim claim) {
        var generation = operations.startGeneration(claim.runId(), claim.modelId());
        Outcome outcome = Outcome.FAILED;
        try {
            if (deletionJournal.blocksAccount(claim.accountId())
                || !runs.mayGenerate(claim.runId(), claim.leaseToken())) {
                throw ReleasedWork.INSTANCE;
            }
            var model = models.require(claim.modelId());
            if (!model.version().equals(claim.modelVersion())) {
                throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE",
                    "The recorded model version is unavailable.");
            }
            outcome = switch (generate(claim, model, generation)) {
                case COMPLETED -> Outcome.COMPLETED;
                case CANCELLED -> Outcome.CANCELLED;
                case EMPTY_OUTPUT -> Outcome.PROVIDER_UNAVAILABLE;
                case LEASE_LOST -> Outcome.LEASE_LOST;
            };
        } catch (ReleasedWork ignored) {
            outcome = Outcome.LEASE_LOST;
        } catch (AssistantApiException failure) {
            if (Thread.currentThread().isInterrupted()) {
                outcome = Outcome.INTERRUPTED;
            } else {
                outcome = switch (failure.code()) {
                    case "PROVIDER_UNAVAILABLE" -> Outcome.PROVIDER_UNAVAILABLE;
                    case "RATE_LIMITED" -> Outcome.RATE_LIMITED;
                    default -> Outcome.FAILED;
                };
            }
            failUnlessInterrupted(claim, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            outcome = Thread.currentThread().isInterrupted() ? Outcome.INTERRUPTED : Outcome.FAILED;
            log.warn("Assistant run {} worker failed ({})", claim.runId(),
                failure.getClass().getSimpleName());
            failUnlessInterrupted(claim, "INTERNAL_ERROR",
                "The assistant could not complete this run.");
        } finally {
            generation.finish(outcome);
        }
    }

    private AssistantRuns.FinishOutcome generate(AssistantRuns.Claim claim,
        AssistantModels.Model model, Generation generation) {
        if (claim.historical()) {
            return generateHistorical(claim, model, generation);
        }
        if (claim.customAssistant() != null) {
            return generateCustom(claim, model, generation);
        }
        if (claim.context() != null && models.supportsLocalMemory(model)
            && generationMemory.configured()) {
            int room = Math.min(model.contextBudget(), claim.context().budget())
                - claim.context().used();
            var selected = generationMemory.select(claim.runId(), claim.leaseToken(), room,
                claim.additionalSlots());
            if (selected.isPresent()) {
                var context = selected.orElseThrow();
                var proof = context.proof();
                if (!generationMemory.lockAndValidate(claim.runId(), claim.leaseToken(), proof)) {
                    return runs.cancelClaim(claim.runId(), claim.leaseToken());
                }
                StringBuilder output = new StringBuilder();
                runs.requireGenerationPolicy(claim);
                models.generateWithLocalMemory(model, claim.messages(), context.material(),
                    delta -> {
                        generation.firstDelta();
                        output.append(delta);
                    });
                return runs.publishPersonalized(claim, proof, output.toString());
            }
            if (deletionJournal.blocksAccount(claim.accountId())) {
                return runs.cancelClaim(claim.runId(), claim.leaseToken());
            }
            if (!runs.mayGenerate(claim.runId(), claim.leaseToken())) {
                return AssistantRuns.FinishOutcome.LEASE_LOST;
            }
        }
        runs.requireGenerationPolicy(claim);
        models.generate(claim.modelId(), claim.messages(), delta -> {
            generation.firstDelta();
            if (deletionJournal.blocksAccount(claim.accountId())
                || !runs.append(claim.runId(), claim.leaseToken(), delta)) {
                throw ReleasedWork.INSTANCE;
            }
        });
        return runs.finish(claim.runId(), claim.leaseToken());
    }

    private AssistantRuns.FinishOutcome generateHistorical(AssistantRuns.Claim claim,
        AssistantModels.Model model, Generation generation) {
        if (!models.supportsLocalMemory(model)) {
            return runs.cancelClaim(claim.runId(), claim.leaseToken());
        }
        var selected = runs.prepareHistorical(claim);
        if (selected.isEmpty()) {
            return runs.cancelClaim(claim.runId(), claim.leaseToken());
        }
        var material = selected.orElseThrow();
        StringBuilder output = new StringBuilder();
        runs.requireGenerationPolicy(claim);
        models.generateWithLocalMemory(model, claim.messages(), List.of(material), delta -> {
            generation.firstDelta();
            output.append(delta);
        });
        return runs.publishHistorical(claim,
            material.content().codePointCount(0, material.content().length()), output.toString());
    }

    private AssistantRuns.FinishOutcome generateCustom(AssistantRuns.Claim claim,
        AssistantModels.Model model, Generation generation) {
        if (!models.supportsLocalMemory(model)) {
            return runs.cancelClaim(claim.runId(), claim.leaseToken());
        }
        try {
            var prepared = runs.prepareCustom(claim);
            if (prepared.isEmpty()) {
                return AssistantRuns.FinishOutcome.LEASE_LOST;
            }
            var selected = prepared.orElseThrow();
            StringBuilder output = new StringBuilder();
            Consumer<String> collect = delta -> {
                generation.firstDelta();
                output.append(delta);
            };
            runs.requireGenerationPolicy(claim);
            if (selected.isEmpty()) {
                models.generate(model.id(), claim.messages(), collect);
            } else {
                models.generateWithLocalMemory(model, claim.messages(), selected, collect);
            }
            return runs.publishCustom(claim, selected, output.toString());
        } catch (AssistantApiException exception) {
            return switch (exception.code()) {
                case "UNAUTHENTICATED", "NOT_FOUND", "FORBIDDEN", "POLICY_DISABLED", "CONFLICT" ->
                    runs.cancelClaim(claim.runId(), claim.leaseToken());
                default -> throw exception;
            };
        }
    }

    private void failUnlessInterrupted(AssistantRuns.Claim claim, String code, String message) {
        if (!Thread.currentThread().isInterrupted()) {
            runs.fail(claim.runId(), claim.leaseToken(), code, message);
        }
    }

    private static final class ReleasedWork extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private static final ReleasedWork INSTANCE = new ReleasedWork();

        private ReleasedWork() {
            super(null, null, false, false);
        }
    }
}
