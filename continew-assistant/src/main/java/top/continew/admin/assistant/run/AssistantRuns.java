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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess.Witness;
import top.continew.admin.assistant.customassistant.AssistantCustomAssistants;
import top.continew.admin.assistant.memory.AssistantGenerationMemory;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Kind;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Material;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Proof;
import top.continew.admin.assistant.model.AssistantModels;
import top.continew.admin.assistant.model.AssistantModels.Turn;
import top.continew.admin.assistant.organization.AssistantOrganizationModelBudgets;
import top.continew.admin.assistant.organization.AssistantOrganizationPolicies;
import top.continew.admin.assistant.organization.AssistantOrganizationPolicies.Enforcement;
import top.continew.admin.assistant.project.AssistantProjectResources;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable branch, run, event and consumption operations.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantRuns {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String QUEUED = "queued";
    private static final String RUNNING = "running";
    private static final String COMPLETED = "completed";
    private static final String CANCELLED = "cancelled";
    private static final String FAILED = "failed";
    private static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    private static final int MAX_PROMPT_UNITS = 8_000;
    private static final int MAX_OUTPUT_UNITS = 8_192;
    private static final int CUSTOM_INPUT_UNITS = 2_000;
    private static final int MAX_KNOWLEDGE_EXCERPT = 400;
    private static final int MAX_KNOWLEDGE_QUERY = 100;
    private static final String PROJECT_LOOKUP = "project_resource_lookup";
    private static final long DEFAULT_QUOTA = 1_000_000;
    private static final long LEASE_MILLIS = 120_000;
    private static final int BATCH_SIZE = 128;
    private static final String ACTIVE_MEMBERSHIP = """
        AND EXISTS (
            SELECT 1 FROM assistant_workspace_member m
            WHERE m.account_id = c.account_id AND m.workspace_id = c.workspace_id
        )
        """;
    private static final String ACTIVE_CONVERSATION = """
        AND EXISTS (
            SELECT 1 FROM assistant_conversation c
            WHERE c.id = r.conversation_id AND c.account_id = r.account_id
              AND c.workspace_id = r.workspace_id AND c.deleted_at IS NULL AND c.read_locked = FALSE
        """ + ACTIVE_MEMBERSHIP + ")";
    private static final RowMapper<RunRow> RUN_ROW = (row, index) -> new RunRow(
        row.getString("id"), row.getString("workspace_id"), row.getString("account_id"),
        row.getString("conversation_id"), row.getString("prompt_message_id"),
        row.getString("response_message_id"), row.getString("request_digest"),
        row.getString("state"),
        row.getLong("last_sequence"), row.getLong("first_sequence"), row.getString("content"),
        row.getString("model_id"), row.getString("model_version"), row.getString("error_code"),
        row.getString("error_message"), AssistantRunContext.read(row),
        row.getString("fallback_from_model_id"),
        customAssistant(row.getString("custom_assistant_id"), row.getString("custom_version_id")),
        row.getString("organization_policy_revision"));
    private static final RowMapper<Message> MESSAGE_ROW = (row, index) -> new Message(
        row.getString("id"), row.getString("parent_id"), row.getString("role"));

    private final JdbcTemplate jdbc;
    private final AssistantModels models;
    private final AssistantAccounts accounts;
    private final AssistantGenerationMemory generationMemory;
    private final AssistantCustomAssistants customAssistants;
    private final AssistantProjectResources projectResources;
    private final AssistantConversationReadAccess readAccess;
    private final AssistantHistoryReferences historyReferences;
    private final AssistantOrganizationPolicies organizationPolicies;
    private final AssistantOrganizationModelBudgets organizationModelBudgets;
    private final int retainedEvents;
    private final long eventTtlMillis;

    /**
     * Creates the durable run service.
     *
     * @param jdbc consumer data source
     * @param models model catalogue and routing
     * @param accounts account lifecycle lock
     * @param generationMemory privacy guard for synthetic personalized publication
     * @param customAssistants owned assistant version registry
     * @param projectResources authorized private project text
     * @param readAccess exact current conversation read proof
     * @param historyReferences explicit prior reply bindings
     * @param organizationPolicies current workspace model admission and publication authority
     * @param organizationModelBudgets verified organization model-unit holds
     * @param retainedEvents maximum events retained per run
     * @param eventTtlMillis event retention age
     */
    public AssistantRuns(JdbcTemplate jdbc, AssistantModels models, AssistantAccounts accounts,
        AssistantGenerationMemory generationMemory,
        @Lazy AssistantCustomAssistants customAssistants,
        @Lazy AssistantProjectResources projectResources,
        AssistantConversationReadAccess readAccess,
        AssistantHistoryReferences historyReferences,
        @Lazy AssistantOrganizationPolicies organizationPolicies,
        @Lazy AssistantOrganizationModelBudgets organizationModelBudgets,
        @Value("${assistant.runs.retained-events:256}") int retainedEvents,
        @Value("${assistant.runs.event-ttl-ms:86400000}") long eventTtlMillis) {
        if (retainedEvents < 1 || retainedEvents > 10_000 || eventTtlMillis < 1000) {
            throw new IllegalArgumentException("Invalid run event retention settings");
        }
        this.jdbc = jdbc;
        this.models = models;
        this.accounts = accounts;
        this.generationMemory = generationMemory;
        this.customAssistants = customAssistants;
        this.projectResources = projectResources;
        this.readAccess = readAccess;
        this.historyReferences = historyReferences;
        this.organizationPolicies = organizationPolicies;
        this.organizationModelBudgets = organizationModelBudgets;
        this.retainedEvents = retainedEvents;
        this.eventTtlMillis = eventTtlMillis;
    }

    /**
     * Reserves usage and atomically persists a branch and its run.
     *
     * @param actor authenticated consumer
     * @param conversationId target conversation
     * @param idempotencyKey caller's stable operation key
     * @param input run command
     * @return original or new run snapshot
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Snapshot enqueue(Principal actor, String conversationId, String idempotencyKey,
        Request input) {
        validate(idempotencyKey, input);
        String account = actor.user().id();
        String workspace = actor.user().workspaceId();
        String keyDigest = digest(idempotencyKey);
        String previousDigest = digest(workspace, conversationId, input.action(), input.content(),
            input.parentId(), input.messageId(), input.modelId());
        CustomAssistant custom = input.customAssistant();
        var source = input.historyReference();
        if (source != null && conversationId.equals(source.sourceConversationId())) {
            throw AssistantHistoryReferences.invalid();
        }
        String ordinaryDigest = custom == null ? previousDigest
            : digest(previousDigest, custom.id(), custom.versionId());
        String requestDigest = source == null ? ordinaryDigest
            : digest(ordinaryDigest, "history_reply", source.sourceConversationId(),
                source.sourceMessageId());

        // An account lock serializes all reservations and uses of the same idempotency key.
        accounts.lockActive(account);
        readAccess.require(account, workspace, conversationId);
        if (!conversationActive(conversationId, account, workspace, true)) {
            throw notFound();
        }
        Optional<RunRow> existing = jdbc.query(
            "SELECT * FROM assistant_run WHERE account_id = ? AND idempotency_key = ?",
            RUN_ROW, account, keyDigest).stream().findFirst();
        if (existing.isPresent()) {
            RunRow run = existing.orElseThrow();
            if (!requestDigest.equals(run.requestDigest())) {
                throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "Idempotency key already belongs to another command.");
            }
            return run.snapshot();
        }

        Enforcement organizationPolicy = organizationPolicies.effective(workspace, true);
        var selection = organizationModel(organizationPolicy, input.modelId());
        var model = selection.model();
        AssistantHistoryReferences.Binding history = null;
        if (source != null) {
            if (!models.supportsLocalMemory(model) || selection.fallbackFromModelId() != null) {
                throw AssistantHistoryReferences.unavailable();
            }
            history = historyReferences.admit(actor.user(), conversationId, source);
        }
        if (custom != null) {
            if (!models.supportsLocalMemory(model)) {
                throw new AssistantApiException(HttpStatus.FORBIDDEN, "POLICY_DISABLED",
                    "The selected model is not authorized for private assistant runs.");
            }
            customAssistants.resolveForRun(actor.user(), custom.id(), custom.versionId(),
                conversationProject(actor.user(), conversationId));
        }
        long now = System.currentTimeMillis();
        String prompt = createPrompt(conversationId, input, now);
        // The extra ancestor discloses partial history even when the message-count limit is reached first.
        List<Turn> turns = branch(conversationId, prompt, AssistantRunContext.MAX_TURNS + 1);
        AssistantRunContext fitted;
        if (history != null) {
            fitted = AssistantRunContext.fit(turns,
                model.contextBudget() - AssistantHistoryReferences.MAX_POINTS,
                AssistantRunContext.MAX_TURNS - 1);
        } else if (custom != null) {
            fitted = AssistantRunContext.fit(turns, model.contextBudget() - CUSTOM_INPUT_UNITS,
                AssistantRunContext.MAX_TURNS - 2);
        } else {
            fitted = AssistantRunContext.fit(turns, model.contextBudget());
        }
        var context = new AssistantRunContext(model.contextBudget(), fitted.used(), fitted.turns(),
            fitted.trimmed());
        String runId = UUID.randomUUID().toString();
        jdbc.update(
            """
                INSERT INTO assistant_run
                    (id, workspace_id, account_id, conversation_id, prompt_message_id, idempotency_key, request_digest,
                     state, last_sequence, first_sequence, content, model_id, model_version, created_at, updated_at,
                     context_budget, context_used, context_turns, context_trimmed, fallback_from_model_id,
                     custom_assistant_id, custom_version_id, organization_policy_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'queued', 0, 1, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            runId, workspace, account, conversationId, prompt, keyDigest, requestDigest,
            model.id(), model.version(), now, now, context.budget(), context.used(),
            context.turns(),
            context.trimmed(), selection.fallbackFromModelId(), custom == null ? null : custom.id(),
            custom == null ? null : custom.versionId(), organizationPolicy.revision());
        if (history != null) {
            historyReferences.persist(runId, actor.user(), history);
        }
        reserve(account, runId);
        touchConversation(conversationId, now);
        organizationModelBudgets.protectAdmission(workspace, models.supportsLocalMemory(model),
            now);
        log.info("Assistant run {} queued", runId);
        return new Snapshot(runId, conversationId, QUEUED, 0, "", null, model.id(), model.version(),
            null, context, selection.fallbackFromModelId(), custom);
    }

    /**
     * Reads the current authorized snapshot.
     *
     * @param actor authenticated consumer
     * @param runId run ID
     * @return authoritative run snapshot
     */
    public Snapshot get(Principal actor, String runId) {
        return scoped(actor, runId, false).run().snapshot();
    }

    /**
     * Reads the current allowance and reserved usage for the authenticated account.
     *
     * @param actor authenticated consumer
     * @return account allowance in visible output code points
     */
    public AccountUsage usage(Principal actor) {
        return jdbc
            .query(
                """
                    SELECT limit_units, reserved_units, used_units FROM assistant_usage_account WHERE account_id = ?
                    """,
                (row, index) -> usage(row.getLong("limit_units"), row.getLong("reserved_units"),
                    row.getLong("used_units")),
                actor.user().id())
            .stream().findFirst()
            .orElseGet(() -> usage(DEFAULT_QUOTA, 0, 0));
    }

    private static AccountUsage usage(long limit, long reserved, long used) {
        return new AccountUsage(limit, reserved, used, limit - reserved - used,
            "output_code_points");
    }

    /**
     * Makes cancellation compete atomically with completion.
     *
     * @param actor authenticated consumer
     * @param runId run ID
     * @return committed state after the action
     */
    @Transactional
    public Snapshot cancel(Principal actor, String runId) {
        RunRow run = scoped(actor, runId, true).run();
        if (!run.terminal()) {
            terminate(run, CANCELLED, null, null, null);
        }
        return scoped(actor, runId, false).run().snapshot();
    }

    /**
     * Returns a bounded batch that can be replayed in order.
     *
     * @param actor authenticated consumer
     * @param runId run ID
     * @param afterSequence last applied event
     * @return event batch and end-of-stream indicator
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventBatch events(Principal actor, String runId, long afterSequence) {
        ScopedRun current = scoped(actor, runId, false);
        RunRow run = current.run();
        if (afterSequence < 0 || afterSequence > run.lastSequence()) {
            throw invalid("Invalid event cursor.");
        }
        if (afterSequence < run.firstSequence() - 1) {
            throw expired();
        }
        List<Event> rows = jdbc.query("""
            SELECT sequence, type, delta, message_id, error_code, error_message
            FROM assistant_run_event
            WHERE run_id = ? AND sequence > ? AND sequence <= ? AND created_at >= ?
            ORDER BY sequence LIMIT ?
            """, (row, index) -> new Event(runId, row.getLong("sequence"), row.getString("type"),
            row.getString("delta"), row.getString("message_id"),
            error(row.getString("error_code"), row.getString("error_message"))),
            runId, afterSequence, run.lastSequence(), System.currentTimeMillis() - eventTtlMillis,
            BATCH_SIZE);
        long cursor = afterSequence;
        for (Event event : rows) {
            if (event.sequence() != ++cursor) {
                throw expired();
            }
        }
        if (cursor < run.lastSequence() && rows.size() < BATCH_SIZE) {
            throw expired();
        }
        readAccess.verify(current.proof());
        return new EventBatch(List.copyOf(rows), run.terminal() && cursor == run.lastSequence(),
            current.proof());
    }

    /**
     * Checks the original read generation of a live stream before sending a frame.
     *
     * @param proof version seen when the stream was opened
     */
    public void verifyEventRead(Witness proof) {
        readAccess.verify(proof);
    }

    /**
     * Claims one queued run. A timed-out provider call is settled once rather than silently reissued.
     *
     * @return work lease and its bounded branch context
     */
    @Transactional
    public Optional<Claim> claimNext() {
        long now = System.currentTimeMillis();
        Optional<RunRow> candidate = jdbc.query("""
            SELECT * FROM assistant_run
            WHERE state = 'queued' OR (state = 'running' AND lease_until <= ?)
            ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, RUN_ROW, now).stream().findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        RunRow run = candidate.orElseThrow();
        if (!conversationActive(run.conversationId(), run.accountId(), run.workspaceId(), false)) {
            terminate(run, CANCELLED, null, null, null);
            return Optional.empty();
        }
        if (RUNNING.equals(run.state())) {
            terminate(run, FAILED, null, PROVIDER_UNAVAILABLE, "The generation was interrupted.");
            return Optional.empty();
        }
        List<AssistantModels.Turn> context;
        if (run.context() == null) {
            var legacy =
                branch(run.conversationId(), run.promptId(), AssistantRunContext.MAX_TURNS);
            int count =
                AssistantRunContext.fit(legacy, AssistantModels.MAX_CONTEXT_CODE_POINTS).turns();
            context = legacy.subList(legacy.size() - count, legacy.size());
        } else {
            context = branch(run.conversationId(), run.promptId(), run.context().turns());
        }
        String token = UUID.randomUUID().toString();
        jdbc.update(
            "UPDATE assistant_run SET state = 'running', lease_token = ?, lease_until = ? WHERE id = ?",
            token, now + LEASE_MILLIS, run.id());
        appendEvent(run, "run.started", null, null, null, null, now);
        log.info("Assistant run {} started", run.id());
        return Optional.of(
            new Claim(run.id(), token, run.modelId(), run.modelVersion(), List.copyOf(context),
                run.accountId(), run.workspaceId(), run.context(), run.customAssistant(),
                historyReferences.exists(run.id())));
    }

    /**
     * Rechecks a queued claim immediately before starting the model.
     *
     * @param runId leased run ID
     * @param leaseToken current run lease
     * @return whether this run may still start generation
     */
    @Transactional
    public boolean mayGenerate(String runId, String leaseToken) {
        Optional<RunRow> active = leased(runId, leaseToken);
        if (active.isEmpty()) {
            return false;
        }
        RunRow run = active.orElseThrow();
        if (!conversationActive(run.conversationId(), run.accountId(), run.workspaceId(), false)) {
            terminate(run, CANCELLED, null, null, null);
            return false;
        }
        return true;
    }

    /**
     * Authorizes the current leased transport at its final dispatch boundary.
     *
     * @param claim exact worker lease and run
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void requireGenerationPolicy(Claim claim) {
        RunRow active = leased(claim.runId(), claim.leaseToken())
            .orElseThrow(() -> new AssistantApiException(HttpStatus.CONFLICT, "RUN_LEASE_LOST",
                "The generation lease is no longer current."));
        if (!conversationActive(active.conversationId(), active.accountId(), active.workspaceId(),
            false)) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "RUN_LEASE_LOST",
                "The generation scope is no longer current.");
        }
        requireOrganizationPolicy(active);
    }

    private AssistantModels.Selection organizationModel(Enforcement policy, String requested) {
        if (policy.reviewRequired()
            || policy.modelIds() != null && policy.modelIds().isEmpty()
            || requested != null && !policy.allowsModel(requested)) {
            throw AssistantOrganizationPolicies.denied();
        }
        var selection = models.resolve(requested);
        if (policy.allowsModel(selection.model().id())) {
            return selection;
        }
        if (requested == null) {
            String allowed = models.list().stream().map(AssistantModels.Model::id)
                .filter(policy::allowsModel).findFirst()
                .orElseThrow(AssistantOrganizationPolicies::denied);
            return new AssistantModels.Selection(models.require(allowed), null);
        }
        throw AssistantOrganizationPolicies.denied();
    }

    private void requireOrganizationPolicy(RunRow run) {
        Enforcement current = organizationPolicies.effective(run.workspaceId(), true);
        if (!current.matches(run.organizationPolicyRevision())
            || !current.allowsModel(run.modelId())) {
            throw AssistantOrganizationPolicies.denied();
        }
        organizationModelBudgets.requireCurrent(run.workspaceId(),
            models
                .supportsLocalMemory(new AssistantModels.Model(run.modelId(), run.modelVersion())));
    }

    /**
     * Settles active runs during privacy deletion while the caller holds the account lock.
     *
     * @param accountId owner of the affected runs
     * @param conversationId target conversation or null for all owned conversations
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForPrivacy(String accountId, String conversationId) {
        String query = """
            SELECT * FROM assistant_run WHERE account_id = ? AND state IN ('queued', 'running')
            """;
        String tail = " ORDER BY id FOR UPDATE";
        List<RunRow> active = conversationId == null ? jdbc.query(query + tail, RUN_ROW, accountId)
            : jdbc.query(query + " AND conversation_id = ?" + tail, RUN_ROW, accountId,
                conversationId);
        cancelActive(active);
    }

    /**
     * Settles only live runs in the exact departed workspace while its member lock is held.
     *
     * @param accountId departing user
     * @param workspaceId departed workspace
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForWorkspaceDeparture(String accountId, String workspaceId) {
        List<RunRow> active = jdbc.query("""
            SELECT * FROM assistant_run WHERE account_id = ? AND workspace_id = ?
                AND state IN ('queued', 'running') ORDER BY id FOR UPDATE
            """, RUN_ROW, accountId, workspaceId);
        cancelActive(active);
    }

    /**
     * Cancels live runs whose explicit history permission predates an opt-out.
     *
     * @param accountId consumer account
     * @param workspaceId personal workspace
     * @param cutoff independent history cutoff
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForHistoryOptOut(String accountId, String workspaceId, long cutoff) {
        cancelHistoryBindings(accountId, workspaceId, "b.history_epoch <= ?", cutoff);
    }

    /**
     * Clears live derivations of a source when it is removed or locked.
     *
     * @param accountId conversation owner
     * @param workspaceId source workspace
     * @param conversationId source conversation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForHistorySource(String accountId, String workspaceId,
        String conversationId) {
        cancelHistoryBindings(accountId, workspaceId, "b.source_conversation_id = ?",
            conversationId);
    }

    /**
     * Cancels historical derivations tied to an older placement of either conversation.
     *
     * @param accountId conversation owner
     * @param workspaceId source workspace
     * @param conversationId moved conversation
     * @param revision current independent placement proof
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForHistoryPlacement(String accountId, String workspaceId,
        String conversationId, String revision) {
        cancelHistoryBindings(accountId, workspaceId, """
            (b.source_conversation_id = ? AND COALESCE(b.source_placement_revision, '') <> ?)
            OR (b.target_conversation_id = ? AND COALESCE(b.target_placement_revision, '') <> ?)
            """, conversationId, revision, conversationId, revision);
    }

    private void cancelHistoryBindings(String accountId, String workspaceId,
        String predicate, Object... values) {
        Object[] arguments = new Object[2 + values.length];
        arguments[0] = accountId;
        arguments[1] = workspaceId;
        System.arraycopy(values, 0, arguments, 2, values.length);
        cancelActive(jdbc.query("""
            SELECT r.* FROM assistant_run r
            WHERE r.account_id = ? AND r.workspace_id = ? AND r.state IN ('queued', 'running')
                AND EXISTS (SELECT 1 FROM assistant_run_history_reference b WHERE b.run_id = r.id
                    AND (
            """ + predicate + ")) ORDER BY r.id FOR UPDATE", RUN_ROW, arguments));
    }

    private void cancelActive(List<RunRow> active) {
        for (RunRow run : active) {
            terminate(run, CANCELLED, null, null, null);
        }
    }

    /**
     * Persists a provider fragment only while this lease owns the run.
     *
     * @param runId run ID
     * @param leaseToken current work lease
     * @param delta visible fragment
     * @return true if the lease may produce more output
     */
    @Transactional
    public boolean append(String runId, String leaseToken, String delta) {
        Optional<RunRow> active = leased(runId, leaseToken);
        if (active.isEmpty()) {
            return false;
        }
        RunRow run = active.orElseThrow();
        if (!conversationActive(run.conversationId(), run.accountId(), run.workspaceId(), false)) {
            terminate(run, CANCELLED, null, null, null);
            return false;
        }
        requireOrganizationPolicy(run);
        if (delta == null || delta.isEmpty()) {
            return true;
        }
        String content = run.content() + delta;
        if (units(content) > MAX_OUTPUT_UNITS) {
            terminate(run, FAILED, null, PROVIDER_UNAVAILABLE,
                "The reply exceeded the output limit.");
            return false;
        }
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE assistant_run SET content = ?, lease_until = ? WHERE id = ?",
            content, now + LEASE_MILLIS, run.id());
        appendEvent(run, "message.delta", delta, null, null, null, now);
        return true;
    }

    /**
     * Commits the final assistant message and terminal event in one transaction.
     *
     * @param runId run ID
     * @param leaseToken current work lease
     * @return outcome of the publication attempt
     */
    @Transactional
    public FinishOutcome finish(String runId, String leaseToken) {
        Optional<RunRow> active = leased(runId, leaseToken);
        if (active.isEmpty()) {
            return FinishOutcome.LEASE_LOST;
        }
        return complete(active.orElseThrow());
    }

    /**
     * Settles a claim whose source authorization was lost before model dispatch.
     *
     * @param runId current worker run
     * @param leaseToken current worker lease
     * @return cancellation or a superseded lease
     */
    @Transactional
    public FinishOutcome cancelClaim(String runId, String leaseToken) {
        Optional<RunRow> active = leased(runId, leaseToken);
        if (active.isEmpty()) {
            return FinishOutcome.LEASE_LOST;
        }
        terminate(active.orElseThrow(), CANCELLED, null, null, null);
        return FinishOutcome.CANCELLED;
    }

    /**
     * Selects only the private input allowed by the requested assistant version.
     *
     * @param claim current local worker lease
     * @return private inputs or an empty result when the worker has lost the lease
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<List<Material>> prepareCustom(Claim claim) {
        User owner = lockPrivateScope(claim);
        return leased(claim.runId(), claim.leaseToken())
            .map(run -> customMaterial(owner, run, claim.messages()));
    }

    /**
     * Publishes private output after verifying the version and every input again.
     *
     * @param claim current local worker lease
     * @param selected authorized inputs captured at dispatch
     * @param buffered generated reply
     * @return outcome of the transactional publication
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FinishOutcome publishCustom(Claim claim, List<Material> selected, String buffered) {
        User owner = lockPrivateScope(claim);
        Optional<RunRow> active = leased(claim.runId(), claim.leaseToken());
        if (active.isEmpty()) {
            return FinishOutcome.LEASE_LOST;
        }
        RunRow run = active.orElseThrow();
        if (!selected.equals(customMaterial(owner, run, claim.messages()))) {
            terminate(run, CANCELLED, null, null, null);
            return FinishOutcome.CANCELLED;
        }
        int points = selected.stream().mapToInt(source -> units(source.content())).sum();
        return publishBuffered(run, points, buffered);
    }

    private User lockPrivateScope(Claim claim) {
        accounts.lockActive(claim.accountId());
        if (jdbc.queryForList(
            """
                SELECT workspace_id FROM assistant_workspace_member WHERE account_id = ? AND workspace_id = ?
                FOR UPDATE
                """,
            claim.accountId(), claim.workspaceId()).isEmpty()) {
            throw notFound();
        }
        return new User(claim.accountId(), null, claim.workspaceId(), null);
    }

    private List<Material> customMaterial(User owner, RunRow run, List<Turn> turns) {
        if (run.customAssistant() == null || run.context() == null) {
            throw new IllegalStateException("The run has no private assistant binding.");
        }
        String projectId = conversationProject(owner, run.conversationId());
        CustomAssistant selection = run.customAssistant();
        var version = customAssistants.resolveForRun(owner, selection.id(), selection.versionId(),
            projectId);
        List<Material> material = new ArrayList<>(2);
        String instruction = version.instructions();
        if (!instruction.isBlank()) {
            material.add(new Material(Kind.CUSTOM_INSTRUCTIONS, instruction));
        }
        String query = turns.get(turns.size() - 1).content().strip();
        int capacity = Math.min(CUSTOM_INPUT_UNITS, run.context().budget() - run.context().used())
            - units(instruction);
        if (projectId != null && capacity > 0 && !query.isBlank()
            && units(query) <= MAX_KNOWLEDGE_QUERY
            && version.tools().contains(PROJECT_LOOKUP)) {
            findKnowledge(owner, projectId, version.resourceIds(), query,
                Math.min(capacity, MAX_KNOWLEDGE_EXCERPT)).ifPresent(material::add);
        }
        return List.copyOf(material);
    }

    private Optional<Material> findKnowledge(User owner, String projectId, List<String> resourceIds,
        String query, int maximum) {
        for (String id : resourceIds) {
            var resource = projectResources.get(owner, projectId, id);
            String value = resource.content().strip();
            int match = value.indexOf(query);
            if (match >= 0 || resource.name().contains(query)) {
                int from = Math.max(0, match);
                int available = value.codePointCount(from, value.length());
                int to = value.offsetByCodePoints(from, Math.min(maximum, available));
                return Optional.of(new Material(Kind.CUSTOM_KNOWLEDGE, value.substring(from, to)));
            }
        }
        return Optional.empty();
    }

    /**
     * Publishes a buffered local response only while all selected sources remain authorized.
     *
     * @param claim current worker claim
     * @param proof scoped source proof captured before calling the model
     * @param buffered generated reply
     * @return outcome of the transactional publication
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FinishOutcome publishPersonalized(Claim claim, Proof proof, String buffered) {
        // Follow account-first revocation locking before taking the run lease row.
        boolean allowed =
            generationMemory.lockAndValidate(claim.runId(), claim.leaseToken(), proof);
        Optional<RunRow> found = leased(claim.runId(), claim.leaseToken());
        if (found.isEmpty()) {
            return FinishOutcome.LEASE_LOST;
        }
        RunRow run = found.orElseThrow();
        if (!allowed) {
            terminate(run, CANCELLED, null, null, null);
            return FinishOutcome.CANCELLED;
        }
        FinishOutcome result =
            publishBuffered(run, proof.points() == 0 ? -1 : proof.points(), buffered);
        if (result == FinishOutcome.COMPLETED && proof.sourceAccess() != null) {
            generationMemory.recordProjectSources(run.id(), proof);
        }
        return result;
    }

    /**
     * Reads bounded prior text only under the current personal source and target policy.
     *
     * @param claim current synthetic generation claim
     * @return ephemeral authorized input
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Material> prepareHistorical(Claim claim) {
        User owner = lockPrivateScope(claim);
        if (leased(claim.runId(), claim.leaseToken()).isEmpty()) {
            return Optional.empty();
        }
        return historyReferences.material(owner, claim.runId());
    }

    /**
     * Commits a buffered generation and its source-use receipt only if policy still permits it.
     *
     * @param claim current synthetic generation claim
     * @param points exact private input length in Unicode code points
     * @param buffered local response held until validation
     * @return transactional outcome
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FinishOutcome publishHistorical(Claim claim, int points, String buffered) {
        User owner = lockPrivateScope(claim);
        Optional<RunRow> active = leased(claim.runId(), claim.leaseToken());
        if (active.isEmpty()) {
            return FinishOutcome.LEASE_LOST;
        }
        RunRow run = active.orElseThrow();
        if (points < 1 || points > AssistantHistoryReferences.MAX_POINTS
            || !historyReferences.mayPublish(owner, run.id())) {
            terminate(run, CANCELLED, null, null, null);
            return FinishOutcome.CANCELLED;
        }
        FinishOutcome result = publishBuffered(run, points, buffered);
        if (result == FinishOutcome.COMPLETED) {
            historyReferences.used(run.id(), System.currentTimeMillis());
        }
        return result;
    }

    private FinishOutcome publishBuffered(RunRow run, int points, String buffered) {
        if (run.context() == null || points < 0
            || points > run.context().budget() - run.context().used()
            || !run.content().isEmpty() || buffered.isBlank()
            || units(buffered) > MAX_OUTPUT_UNITS) {
            terminate(run, FAILED, null, PROVIDER_UNAVAILABLE,
                "The local reply could not be published.");
            return FinishOutcome.EMPTY_OUTPUT;
        }
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE assistant_run SET content = ?, context_used = ? WHERE id = ?",
            buffered, run.context().used() + points, run.id());
        appendEvent(run, "message.delta", buffered, null, null, null, now);
        return complete(jdbc.queryForObject("SELECT * FROM assistant_run WHERE id = ?", RUN_ROW,
            run.id()));
    }

    private FinishOutcome complete(RunRow run) {
        // Serialize publication with conversation deletion and other replies in the same conversation.
        if (!conversationActive(run.conversationId(), run.accountId(), run.workspaceId(), true)) {
            terminate(run, CANCELLED, null, null, null);
            return FinishOutcome.CANCELLED;
        }
        requireOrganizationPolicy(run);
        if (run.content().isBlank()) {
            terminate(run, FAILED, null, PROVIDER_UNAVAILABLE, "The model returned no text.");
            return FinishOutcome.EMPTY_OUTPUT;
        }
        long now = System.currentTimeMillis();
        String response =
            insertMessage(run.conversationId(), run.promptId(), "assistant", run.content(), now);
        terminate(run, COMPLETED, response, null, null);
        touchConversation(run.conversationId(), now);
        return FinishOutcome.COMPLETED;
    }

    /**
     * Results of attempting to publish a model reply.
     */
    public enum FinishOutcome {
        COMPLETED, CANCELLED, EMPTY_OUTPUT, LEASE_LOST
    }

    /**
     * Records a provider failure only while this worker owns the run.
     *
     * @param runId run ID
     * @param leaseToken current work lease
     * @param errorCode safe consumer error code
     * @param errorMessage safe failure message
     */
    @Transactional
    public void fail(String runId, String leaseToken, String errorCode, String errorMessage) {
        leased(runId, leaseToken)
            .ifPresent(run -> terminate(run, FAILED, null, errorCode, errorMessage));
    }

    /**
     * Removes durable events past the retention age. Snapshots retain the applied output.
     */
    @Scheduled(fixedDelay = 60_000)
    public void purgeExpiredEvents() {
        jdbc.update("DELETE FROM assistant_run_event WHERE created_at < ?",
            System.currentTimeMillis() - eventTtlMillis);
    }

    private ScopedRun scoped(Principal actor, String runId, boolean lock) {
        User user = actor.user();
        String conversation = jdbc
            .queryForList(
                """
                    SELECT conversation_id FROM assistant_run WHERE id = ? AND account_id = ? AND workspace_id = ?
                    """,
                String.class, runId, user.id(), user.workspaceId())
            .stream().findFirst()
            .orElseThrow(AssistantRuns::notFound);
        Witness proof = readAccess.require(user.id(), user.workspaceId(), conversation);
        String sql =
            "SELECT r.* FROM assistant_run r WHERE r.id = ? AND r.account_id = ? AND r.workspace_id = ? "
                + ACTIVE_CONVERSATION + (lock ? " FOR UPDATE" : "");
        var result = jdbc.query(sql, RUN_ROW, runId, user.id(), user.workspaceId()).stream()
            .findFirst();
        readAccess.verify(proof);
        return new ScopedRun(result.orElseThrow(AssistantRuns::notFound), proof);
    }

    private Optional<RunRow> leased(String runId, String token) {
        return jdbc.query("""
            SELECT * FROM assistant_run WHERE id = ? AND lease_token = ? AND lease_until > ?
              AND state = 'running' FOR UPDATE
            """, RUN_ROW, runId, token, System.currentTimeMillis()).stream().findFirst();
    }

    private boolean conversationActive(String conversationId, String accountId, String workspaceId,
        boolean lock) {
        if (lock && jdbc.queryForList("""
            SELECT id FROM assistant_conversation
            WHERE id = ? AND account_id = ? AND workspace_id = ? AND deleted_at IS NULL FOR UPDATE
            """, String.class, conversationId, accountId, workspaceId).isEmpty()) {
            return false;
        }
        // Workers hold a run lock here. Never wait on an in-flight membership or policy transition.
        try {
            readAccess.require(accountId, workspaceId, conversationId);
            return true;
        } catch (AssistantApiException error) {
            if (error.status() == HttpStatus.NOT_FOUND || error.status() == HttpStatus.LOCKED) {
                return false;
            }
            throw error;
        }
    }

    private String conversationProject(User owner, String conversationId) {
        List<String> project = jdbc.queryForList("""
            SELECT c.project_id FROM assistant_conversation c
            WHERE c.id = ? AND c.account_id = ? AND c.workspace_id = ? AND c.deleted_at IS NULL
            """ + ACTIVE_MEMBERSHIP, String.class, conversationId, owner.id(), owner.workspaceId());
        if (project.isEmpty()) {
            throw notFound();
        }
        return project.get(0);
    }

    private void appendEvent(RunRow run, String type, String delta, String messageId,
        String errorCode, String errorMessage, long now) {
        long sequence = run.lastSequence() + 1;
        long floor = Math.max(run.firstSequence(), sequence - retainedEvents + 1);
        jdbc.update(
            "UPDATE assistant_run SET last_sequence = ?, first_sequence = ?, updated_at = ? WHERE id = ?",
            sequence, floor, now, run.id());
        jdbc.update(
            """
                INSERT INTO assistant_run_event (run_id, sequence, type, delta, message_id, error_code, error_message,
                    created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            run.id(), sequence, type, delta, messageId, errorCode, errorMessage, now);
        if (floor > run.firstSequence()) {
            jdbc.update("DELETE FROM assistant_run_event WHERE run_id = ? AND sequence < ?",
                run.id(), floor);
        }
    }

    private void terminate(RunRow run, String state, String messageId, String code, String detail) {
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                UPDATE assistant_run SET state = ?, response_message_id = ?, error_code = ?, error_message = ?,
                    lease_token = NULL, lease_until = NULL, finished_at = ? WHERE id = ?
                """,
            state, messageId, code, detail, now, run.id());
        appendEvent(run, "run." + state, null, messageId, code, detail, now);
        int consumed = units(run.content());
        if (jdbc.update(
            "UPDATE assistant_run_charge SET settled_units = ? WHERE run_id = ? AND settled_units IS NULL",
            consumed, run.id()) == 1) {
            Long reserved = jdbc.queryForObject(
                "SELECT reserved_units FROM assistant_run_charge WHERE run_id = ?",
                Long.class, run.id());
            jdbc.update(
                """
                    UPDATE assistant_usage_account SET used_units = used_units + ?, reserved_units = reserved_units - ?
                    WHERE account_id = ?
                    """,
                consumed, reserved, run.accountId());
        }
        log.info("Assistant run {} finalized as {}", run.id(), state);
    }

    private void reserve(String accountId, String runId) {
        if (jdbc
            .queryForList("SELECT account_id FROM assistant_usage_account WHERE account_id = ?",
                accountId)
            .isEmpty()) {
            jdbc.update(
                """
                    INSERT INTO assistant_usage_account (account_id, limit_units, reserved_units, used_units)
                    VALUES (?, ?, 0, 0)
                    """,
                accountId, DEFAULT_QUOTA);
        }
        int updated = jdbc.update("""
            UPDATE assistant_usage_account SET reserved_units = reserved_units + ?
            WHERE account_id = ? AND used_units + reserved_units + ? <= limit_units
            """, MAX_OUTPUT_UNITS, accountId, MAX_OUTPUT_UNITS);
        if (updated != 1) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "There is not enough generation quota.");
        }
        jdbc.update(
            "INSERT INTO assistant_run_charge (run_id, account_id, reserved_units) VALUES (?, ?, ?)",
            runId, accountId, MAX_OUTPUT_UNITS);
    }

    private String createPrompt(String conversation, Request input, long now) {
        return switch (input.action()) {
            case "send" -> {
                String parent = input.parentId() == null
                    ? jdbc.query("""
                        SELECT id FROM assistant_chat_message WHERE conversation_id = ?
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """, (row, index) -> row.getString("id"), conversation).stream().findFirst()
                        .orElse(null)
                    : message(conversation, input.parentId()).id();
                yield insertMessage(conversation, parent, "user", input.content(), now);
            }
            case "edit" -> {
                Message edited = message(conversation, input.messageId());
                if (!"user".equals(edited.role())) {
                    throw invalid("Only user messages can be edited.");
                }
                yield insertMessage(conversation, edited.parentId(), "user", input.content(), now);
            }
            default -> {
                Message target = message(conversation, input.messageId());
                Message prompt = "user".equals(target.role())
                    ? target : message(conversation, target.parentId());
                if (!"user".equals(prompt.role())) {
                    throw invalid("The branch has no user prompt.");
                }
                yield prompt.id();
            }
        };
    }

    private List<AssistantModels.Turn> branch(String conversationId, String promptId, int limit) {
        return jdbc.query("""
            WITH RECURSIVE branch (id, parent_id, role, content, depth) AS (
                SELECT id, parent_id, role, content, 0
                FROM assistant_chat_message WHERE conversation_id = ? AND id = ?
                UNION ALL
                SELECT m.id, m.parent_id, m.role, m.content, b.depth + 1
                FROM assistant_chat_message m JOIN branch b ON m.id = b.parent_id
                WHERE m.conversation_id = ? AND b.depth + 1 < ?
            )
            SELECT role, content FROM branch ORDER BY depth DESC
            """,
            (row, index) -> new AssistantModels.Turn(row.getString("role"),
                row.getString("content")),
            conversationId, promptId, conversationId, limit);
    }

    private Message message(String conversationId, String messageId) {
        return jdbc
            .query(
                """
                    SELECT id, parent_id, role FROM assistant_chat_message WHERE conversation_id = ? AND id = ?
                    """,
                MESSAGE_ROW, conversationId, messageId)
            .stream().findFirst().orElseThrow(AssistantRuns::notFound);
    }

    private String insertMessage(String conversation, String parent, String role, String content,
        long now) {
        String id = UUID.randomUUID().toString();
        // Keep a branch parent before its child even when both operations share the clock tick.
        long createdAt = parent == null ? now : Math.max(now, jdbc.queryForObject("""
            SELECT created_at FROM assistant_chat_message WHERE conversation_id = ? AND id = ?
            """, Long.class, conversation, parent) + 1);
        jdbc.update(
            """
                INSERT INTO assistant_chat_message (id, conversation_id, parent_id, role, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            id, conversation, parent, role, content, createdAt);
        return id;
    }

    private void touchConversation(String conversationId, long now) {
        jdbc.update(
            """
                UPDATE assistant_conversation SET updated_at = CASE WHEN updated_at > ? THEN updated_at ELSE ? END
                WHERE id = ? AND deleted_at IS NULL
                """,
            now, now, conversationId);
    }

    private static void validate(String key, Request input) {
        if (key == null || !KEY.matcher(key).matches() || input == null || input.action() == null) {
            throw invalid("An action and a valid Idempotency-Key are required.");
        }
        boolean contentValid = input.content() != null && !input.content().isBlank()
            && units(input.content()) <= MAX_PROMPT_UNITS && input.content().indexOf(0) == -1;
        boolean valid = switch (input.action()) {
            case "send" -> contentValid && input.messageId() == null;
            case "edit" -> contentValid && input.messageId() != null && input.parentId() == null;
            case "regenerate" ->
                input.messageId() != null && input.content() == null && input.parentId() == null;
            default -> false;
        };
        if (!valid) {
            throw invalid("Invalid run command. Message content is limited to 8000 characters.");
        }
        if (input.customAssistant() != null && (input.customAssistant().id() == null
            || input.customAssistant().versionId() == null)) {
            throw invalid("A private assistant and its version are required together.");
        }
        var source = input.historyReference();
        if (source != null && (!"send".equals(input.action()) || input.customAssistant() != null
            || !AssistantHistoryReferences.uuid(source.sourceConversationId())
            || !AssistantHistoryReferences.uuid(source.sourceMessageId()))) {
            throw AssistantHistoryReferences.invalid();
        }
    }

    private static int units(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String digest(String... parts) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part == null ? null : part.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) {
                    hash.update(bytes);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ApiError error(String code, String detail) {
        return code == null ? null : new ApiError(code, detail);
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Run or conversation not found.");
    }

    private static AssistantApiException expired() {
        return new AssistantApiException(HttpStatus.GONE, "CURSOR_EXPIRED",
            "The event cursor expired. Read the run snapshot to continue.");
    }

    /**
     * Consumer command for a conversation branch.
     *
     * @param action send, edit or regenerate
     * @param content new user message
     * @param parentId optional branch parent
     * @param messageId target message for edit or regenerate
     * @param modelId optional selected model
     * @param customAssistant explicit private assistant version
     * @param historyReference one explicitly selected prior reply
     */
    public record Request(String action, String content, String parentId, String messageId,
        String modelId, CustomAssistant customAssistant,
        AssistantHistoryReferences.Source historyReference) {
    }

    /**
     * The caller's explicit private assistant selection.
     *
     * @param id owned assistant
     * @param versionId reviewed head version
     */
    public record CustomAssistant(String id, String versionId) {
    }

    private static CustomAssistant customAssistant(String id, String versionId) {
        return id == null ? null : new CustomAssistant(id, versionId);
    }

    /**
     * Authoritative view of applied results.
     *
     * @param runId run ID
     * @param conversationId owning conversation
     * @param state durable run state
     * @param lastSequence latest applied event
     * @param content applied output
     * @param messageId final message if available
     * @param modelId selected model
     * @param modelVersion pinned model version
     * @param error terminal failure if available
     * @param context recorded generation context limits
     * @param fallbackFromModelId retired model requested in this run, when applicable
     * @param customAssistant explicit assistant and version applied to the run
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(String runId, String conversationId, String state, long lastSequence,
        String content, String messageId, String modelId, String modelVersion, ApiError error,
        AssistantRunContext context, String fallbackFromModelId, CustomAssistant customAssistant) {
    }

    /**
     * Account-wide allowance and current usage.
     *
     * @param limit total allowance
     * @param reserved allowance held by nonterminal runs
     * @param used settled usage
     * @param available amount currently available for new runs
     * @param unit metering unit
     */
    public record AccountUsage(long limit, long reserved, long used, long available, String unit) {
    }

    /**
     * One ordered event from the run ledger.
     *
     * @param runId run ID
     * @param sequence event position
     * @param type event type
     * @param delta incremental text, when applicable
     * @param messageId completed message, when applicable
     * @param error terminal failure, when applicable
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(String runId, long sequence, String type, String delta, String messageId,
        ApiError error) {
    }

    /**
     * An authorized event batch.
     *
     * @param items ordered retained events
     * @param terminal whether the batch reaches a terminal boundary
     * @param proof private stream generation proof
     */
    public record EventBatch(List<Event> items, boolean terminal, Witness proof) {
    }

    private record ScopedRun(RunRow run, Witness proof) {
    }

    /**
     * Work leased to a local generation worker.
     *
     * @param runId run ID
     * @param leaseToken unique claim token
     * @param modelId selected model
     * @param modelVersion pinned model version
     * @param messages bounded context on the selected branch
     * @param accountId consumer account owning the run
     * @param workspaceId consumer workspace
     * @param context original recorded branch budget for new runs
     * @param customAssistant requested private assistant version, when applicable
     * @param historical whether an explicit historical reply is bound to this run
     */
    public record Claim(String runId, String leaseToken, String modelId, String modelVersion,
        List<Turn> messages, String accountId, String workspaceId, AssistantRunContext context,
        CustomAssistant customAssistant, boolean historical) {

        /**
         * Returns the remaining number of input slots without dropping a selected branch turn.
         *
         * @return space available for private material
         */
        public int additionalSlots() {
            return AssistantRunContext.MAX_TURNS - messages.size();
        }
    }

    private record RunRow(String id, String workspaceId, String accountId, String conversationId,
        String promptId,
        String responseMessageId, String requestDigest, String state, long lastSequence,
        long firstSequence, String content, String modelId, String modelVersion, String errorCode,
        String errorMessage, AssistantRunContext context, String fallbackFromModelId,
        CustomAssistant customAssistant, String organizationPolicyRevision) {

        private Snapshot snapshot() {
            return new Snapshot(id, conversationId, state, lastSequence, content, responseMessageId,
                modelId, modelVersion, error(errorCode, errorMessage), context,
                fallbackFromModelId, customAssistant);
        }

        private boolean terminal() {
            return !QUEUED.equals(state) && !RUNNING.equals(state);
        }
    }

    private record Message(String id, String parentId, String role) {
    }
}
