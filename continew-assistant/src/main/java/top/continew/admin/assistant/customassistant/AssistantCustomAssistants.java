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

package top.continew.admin.assistant.customassistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjectResources;
import top.continew.admin.assistant.project.AssistantProjects;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns private versioned assistant definitions and validates pinned run context.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantCustomAssistants {

    private static final int MAX_ASSISTANTS = 16;
    private static final int MAX_VERSIONS = 20;
    private static final int MAX_REFERENCES = 8;
    private static final String ACTIVE = "active";
    private static final String REVIEW = "requires_review";
    private static final String TOOL = "project_resource_lookup";
    private static final String ASSISTANTS =
        """
            SELECT a.id, a.project_id, a.head_version_id, a.head_epoch, a.request_digest,
                a.created_at, a.updated_at, v.version_no, v.name
            FROM assistant_custom_assistant a
            JOIN assistant_custom_assistant_version v ON v.assistant_id = a.id AND v.id = a.head_version_id
            WHERE a.account_id = ? AND a.workspace_id = ? AND (a.project_id IS NULL OR EXISTS (
                SELECT 1 FROM assistant_project p WHERE p.id = a.project_id AND p.account_id = a.account_id
                    AND p.workspace_id = a.workspace_id AND p.deleted_at IS NULL
            ))
            """;
    private static final String VERSIONS = """
        SELECT id, assistant_id, version_no, name, instructions, tool_id,
            restored_from_version_id, created_at
        FROM assistant_custom_assistant_version WHERE assistant_id = ?
        """;
    private static final RowMapper<Stored> STORED = (row, index) -> new Stored(row.getString("id"),
        row.getString("project_id"), row.getString("head_version_id"), row.getLong("head_epoch"),
        row.getString("request_digest"), Instant.ofEpochMilli(row.getLong("created_at")),
        Instant.ofEpochMilli(row.getLong("updated_at")), row.getInt("version_no"),
        row.getString("name"));
    private static final RowMapper<StoredVersion> VERSION = (row, index) -> new StoredVersion(
        row.getString("id"), row.getString("assistant_id"), row.getInt("version_no"),
        row.getString("name"),
        row.getString("instructions"), row.getString("tool_id"),
        row.getString("restored_from_version_id"),
        Instant.ofEpochMilli(row.getLong("created_at")));

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantProjects projects;
    private final AssistantProjectResources resources;
    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private final boolean enabled;

    /**
     * Wires the owner registry and scopes private text through the existing project service.
     *
     * @param jdbc product data store
     * @param accounts account locking and authorization
     * @param projects project ownership
     * @param resources scoped, journal-aware text material access
     * @param journal durable head and resource revocations
     * @param erasure scoped revocation application
     * @param enabled custom assistant opt-in
     */
    public AssistantCustomAssistants(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantProjects projects, AssistantProjectResources resources,
        AssistantDeletionJournal journal,
        AssistantErasure erasure,
        @Value("${assistant.custom-assistants.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.projects = projects;
        this.resources = resources;
        this.journal = journal;
        this.erasure = erasure;
        this.enabled = enabled;
    }

    /**
     * Reports whether the deployment has enabled this private registry.
     *
     * @return whether the registry and its durable policy are available
     */
    public boolean available() {
        return enabled && journal.acceptsErasure();
    }

    /**
     * Creates a private assistant or returns a matching original request.
     *
     * @param owner authenticated account and workspace
     * @param key client UUID for retry
     * @param projectId optional fixed project scope
     * @param draft first immutable version
     * @return newly created or previously accepted assistant
     */
    @Transactional
    public Assistant create(User owner, String key, String projectId, Draft draft) {
        requireReady();
        requireId(key);
        Definition definition = normalize(draft, projectId);
        scope(owner, true);
        String id = createId(owner, key);
        requireLive(owner, id);
        String fingerprint = fingerprint(projectId, definition);
        var existing = jdbc.query(ASSISTANTS + " AND a.id = ?", STORED, owner.id(),
            owner.workspaceId(), id).stream().findFirst();
        if (existing.isPresent()) {
            Stored row = existing.orElseThrow();
            if (!row.requestDigest().equals(fingerprint)) {
                throw conflict("This request key belongs to a different assistant.");
            }
            return assistant(owner, row);
        }
        if (cutoff(owner, id) > 0) {
            throw notFound();
        }
        if (jdbc
            .queryForList("SELECT id FROM assistant_custom_assistant WHERE account_id = ? LIMIT 16",
                String.class, owner.id())
            .size() >= MAX_ASSISTANTS) {
            throw conflict("The account has reached its private assistant limit.");
        }
        verifyReferences(owner, projectId, definition.resourceIds());
        String versionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                INSERT INTO assistant_custom_assistant
                    (id, account_id, workspace_id, project_id, head_version_id, head_epoch, request_digest,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
                """,
            id, owner.id(), owner.workspaceId(), projectId, versionId, fingerprint, now, now);
        putVersion(owner, id, projectId, versionId, 1, definition, null, now);
        return assistant(owner, owned(owner, id));
    }

    /**
     * Returns a bounded metadata catalog of only the current owner's assistants.
     *
     * @param owner selected owner
     * @return current private catalog
     */
    public Page<Summary> list(User owner) {
        requireReady();
        scope(owner, false);
        List<Summary> rows = jdbc
            .query(ASSISTANTS + " ORDER BY a.created_at DESC, a.id DESC LIMIT 16",
                STORED, owner.id(), owner.workspaceId())
            .stream()
            .filter(row -> !blocked(owner, row.id()))
            .map(row -> new Summary(row.id(), row.name(), row.projectId(), row.headVersionId(),
                row.versionNo(), status(owner, row), row.createdAt(), row.updatedAt()))
            .toList();
        return new Page<>(rows, null);
    }

    /**
     * Reads metadata and the current immutable owner definition.
     *
     * @param owner selected owner
     * @param id assistant identifier
     * @return current private assistant
     */
    public Assistant get(User owner, String id) {
        requireReady();
        scope(owner, false);
        return assistant(owner, owned(owner, id));
    }

    /**
     * Lists all retained versions within the bounded definition history.
     *
     * @param owner selected owner
     * @param id assistant identifier
     * @return newest versions first
     */
    public Page<Version> versions(User owner, String id) {
        requireReady();
        scope(owner, false);
        Stored row = owned(owner, id);
        List<Version> items = jdbc.query(VERSIONS + " ORDER BY version_no DESC LIMIT 20", VERSION,
            row.id()).stream().map(this::exposedVersion).toList();
        requireLive(owner, id);
        return new Page<>(items, null);
    }

    /**
     * Returns legacy owner-authored configuration without resolving historical knowledge.
     *
     * @param owner selected owner
     * @param id assistant identifier
     * @param versionId exact historical version
     * @return immutable definition
     */
    public Version version(User owner, String id, String versionId) {
        requireReady();
        scope(owner, false);
        Version version = storedVersion(owned(owner, id).id(), versionId);
        requireLive(owner, id);
        return version;
    }

    /**
     * Appends an immutable version and compares-and-swaps the current head.
     *
     * @param owner selected owner
     * @param id assistant identifier
     * @param baseVersionId client-observed head before modification
     * @param draft requested definition
     * @return newly active definition
     */
    @Transactional
    public Assistant revise(User owner, String id, String baseVersionId, Draft draft) {
        Stored row = writable(owner, id, baseVersionId);
        Definition definition = normalize(draft, row.projectId());
        return activate(owner, row, definition, null);
    }

    /**
     * Creates a fresh immutable head from the selected historical version.
     *
     * @param owner selected owner
     * @param id assistant identifier
     * @param baseVersionId client-observed current head
     * @param versionId historical origin
     * @return newly active rollback version
     */
    @Transactional
    public Assistant rollback(User owner, String id, String baseVersionId, String versionId) {
        Stored row = writable(owner, id, baseVersionId);
        Version historical = storedVersion(row.id(), versionId);
        Definition definition = normalize(new Draft(historical.name(), historical.instructions(),
            historical.resourceIds(), historical.tools()), row.projectId());
        return activate(owner, row, definition, historical.id());
    }

    /**
     * Revokes the assistant and all versions through the independent deletion journal.
     *
     * @param owner selected owner
     * @param id assistant identifier
     */
    @Transactional
    public void delete(User owner, String id) {
        requireReady();
        scope(owner, true);
        owned(owner, id);
        erasure.deleteCustomAssistant(owner, id);
    }

    /**
     * Captures opaque current version identifiers when an authorized run is accepted.
     *
     * @param owner selected account and workspace
     * @param assistantId privately selected assistant
     * @param conversationProjectId exact destination project or null for a standalone conversation
     * @return the authorized assistant and immutable version
     */
    @Transactional(readOnly = true)
    public Binding bindForRun(User owner, String assistantId, String conversationProjectId) {
        requireReady();
        scope(owner, false);
        Stored row = owned(owner, assistantId);
        Version selected = runVersion(owner, row, row.headVersionId(), conversationProjectId);
        return new Binding(row.id(), selected.id());
    }

    /**
     * Revalidates a private version and every selected reference before use or publication.
     *
     * @param owner selected account and workspace
     * @param assistantId previously bound assistant
     * @param versionId immutable version captured at acceptance
     * @param conversationProjectId current conversation project
     * @return current approved definition without fetching remote data
     */
    @Transactional(readOnly = true)
    public Version resolveForRun(User owner, String assistantId, String versionId,
        String conversationProjectId) {
        requireReady();
        scope(owner, false);
        return runVersion(owner, owned(owner, assistantId), versionId, conversationProjectId);
    }

    private Version runVersion(User owner, Stored row, String versionId, String projectId) {
        requireId(versionId);
        if (!Objects.equals(row.projectId(), projectId)) {
            throw notFound();
        }
        if (!row.headVersionId().equals(versionId) || !ACTIVE.equals(status(owner, row))) {
            throw conflict("The assistant definition changed and needs review.");
        }
        Version definition = storedVersion(row.id(), versionId);
        Definition normalized = normalize(new Draft(definition.name(), definition.instructions(),
            definition.resourceIds(), definition.tools()), row.projectId());
        verifyReferences(owner, row.projectId(), normalized.resourceIds());
        requireLive(owner, row.id());
        if (!ACTIVE.equals(status(owner, row))) {
            throw conflict("The assistant definition changed and needs review.");
        }
        return definition;
    }

    private Stored writable(User owner, String id, String baseVersionId) {
        requireReady();
        requireId(baseVersionId);
        scope(owner, true);
        Stored row = owned(owner, id);
        if (!row.headVersionId().equals(baseVersionId)) {
            throw conflict("The current assistant version changed.");
        }
        if (row.versionNo() >= MAX_VERSIONS) {
            throw conflict("The assistant has reached its version limit.");
        }
        return row;
    }

    private Assistant activate(User owner, Stored row, Definition definition, String origin) {
        verifyReferences(owner, row.projectId(), definition.resourceIds());
        String versionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        putVersion(owner, row.id(), row.projectId(), versionId, row.versionNo() + 1, definition,
            origin, now);
        erasure.reviseCustomAssistant(owner, row.id());
        long headEpoch =
            Math.incrementExact(Math.max(now, Math.max(row.headEpoch(), cutoff(owner, row.id()))));
        int changed = jdbc.update(
            """
                UPDATE assistant_custom_assistant SET head_version_id = ?, head_epoch = ?, updated_at = ?
                WHERE id = ? AND account_id = ? AND workspace_id = ? AND head_version_id = ?
                """,
            versionId, headEpoch, now, row.id(), owner.id(), owner.workspaceId(),
            row.headVersionId());
        if (changed != 1) {
            throw conflict("The current assistant version changed.");
        }
        return assistant(owner, owned(owner, row.id()));
    }

    private void putVersion(User owner, String assistantId, String projectId, String versionId,
        int number,
        Definition definition, String origin, long now) {
        jdbc.update(
            """
                INSERT INTO assistant_custom_assistant_version
                    (id, assistant_id, version_no, name, instructions, tool_id, restored_from_version_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            versionId, assistantId, number, definition.name(), definition.instructions(),
            definition.tool(), origin, now);
        List<String> sources = definition.resourceIds();
        for (int index = 0; index < sources.size(); index++) {
            jdbc.update(
                """
                    INSERT INTO assistant_custom_assistant_source
                        (assistant_id, version_id, account_id, workspace_id, project_id, resource_id, position_no)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                assistantId, versionId, owner.id(), owner.workspaceId(), projectId,
                sources.get(index), index + 1);
        }
    }

    private void verifyReferences(User owner, String projectId, List<String> references) {
        if (projectId != null) {
            projects.requireOwned(owner, projectId);
        }
        for (String material : references) {
            resources.get(owner, projectId, material);
        }
    }

    private Assistant assistant(User owner, Stored row) {
        Version head = storedVersion(row.id(), row.headVersionId());
        requireLive(owner, row.id());
        return new Assistant(row.id(), row.projectId(), status(owner, row), head,
            row.createdAt(), row.updatedAt());
    }

    private Version storedVersion(String assistantId, String versionId) {
        requireId(versionId);
        return jdbc.query(VERSIONS + " AND id = ?", VERSION, assistantId, versionId).stream()
            .findFirst().map(this::exposedVersion).orElseThrow(AssistantCustomAssistants::notFound);
    }

    private Version exposedVersion(StoredVersion row) {
        List<String> sources = jdbc.queryForList("""
            SELECT resource_id FROM assistant_custom_assistant_source
            WHERE assistant_id = ? AND version_id = ? ORDER BY position_no LIMIT 8
            """, String.class, row.assistantId(), row.id());
        return new Version(row.id(), row.assistantId(), row.number(), row.name(),
            row.instructions(),
            sources, row.tool() == null ? List.of() : List.of(row.tool()), row.createdAt(),
            row.restoredFromVersionId());
    }

    private Stored owned(User owner, String id) {
        requireId(id);
        requireLive(owner, id);
        Stored row = jdbc.query(ASSISTANTS + " AND a.id = ?", STORED, owner.id(),
            owner.workspaceId(), id).stream().findFirst()
            .orElseThrow(AssistantCustomAssistants::notFound);
        if (row.projectId() != null) {
            projects.requireOwned(owner, row.projectId());
        }
        requireLive(owner, id);
        return row;
    }

    private void scope(User owner, boolean lock) {
        if (lock) {
            accounts.lockActive(owner.id());
        }
        String statement =
            "SELECT account_id FROM assistant_workspace_member WHERE account_id = ? AND workspace_id = ?";
        if (jdbc.queryForList(statement + (lock ? " FOR UPDATE" : ""), String.class, owner.id(),
            owner.workspaceId()).isEmpty()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Workspace access is required.");
        }
    }

    private boolean blocked(User owner, String id) {
        return journal.blocksCustomAssistant(owner.id(), owner.workspaceId(), id);
    }

    private void requireLive(User owner, String id) {
        if (blocked(owner, id)) {
            throw notFound();
        }
    }

    private long cutoff(User owner, String id) {
        return journal.customAssistantCutoff(owner.id(), owner.workspaceId(), id);
    }

    private String status(User owner, Stored row) {
        return row.headEpoch() > cutoff(owner, row.id()) ? ACTIVE : REVIEW;
    }

    private void requireReady() {
        if (!enabled || !journal.enabled()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Private assistants are unavailable.");
        }
        if (!journal.acceptsErasure()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Private assistant revocation is temporarily unavailable.");
        }
    }

    private static Definition normalize(Draft draft, String projectId) {
        if (projectId != null) {
            requireId(projectId);
        }
        String name = text(draft.name(), 120, false);
        String instructions = text(draft.instructions(), 2000, true);
        List<String> sources = draft.resourceIds() == null ? List.of() : draft.resourceIds();
        List<String> tools = draft.tools() == null ? List.of() : draft.tools();
        if (sources.size() > MAX_REFERENCES || new HashSet<>(sources).size() != sources.size()
            || !sources.isEmpty() && projectId == null) {
            throw invalid("Choose up to eight distinct text materials from the linked project.");
        }
        sources.forEach(AssistantCustomAssistants::requireId);
        if (!tools.isEmpty() && (!List.of(TOOL).equals(tools) || sources.isEmpty())) {
            throw invalid("The local resource lookup tool requires a linked text material.");
        }
        return new Definition(name, instructions, List.copyOf(sources),
            tools.isEmpty() ? null : TOOL);
    }

    private static String text(String value, int max, boolean multiline) {
        if (value == null || value.length() > max * 2
            || value.codePointCount(0, value.length()) > max
            || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
            || value.codePoints().anyMatch(point -> Character.isISOControl(point)
                && (!multiline || point != '\t' && point != '\n' && point != '\r'))
            || !multiline && value.isBlank()) {
            throw invalid("The assistant name or instructions are invalid.");
        }
        return multiline ? value : value.strip();
    }

    static String createId(User owner, String key) {
        byte[] id = digest("assistant-custom-assistant/v1", owner.id(), owner.workspaceId(), key);
        id[6] = (byte) ((id[6] & 0x0f) | 0x80);
        id[8] = (byte) ((id[8] & 0x3f) | 0x80);
        var buffer = ByteBuffer.wrap(id);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static String fingerprint(String projectId, Definition definition) {
        return HexFormat.of()
            .formatHex(digest(projectId == null ? "" : projectId, definition.name(),
                definition.instructions(), String.join(",", definition.resourceIds()),
                definition.tool() == null ? "" : definition.tool()));
    }

    private static byte[] digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] input = part.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(input.length).array());
                sha.update(input);
            }
            return sha.digest();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Assistant identity unavailable.", failure);
        }
    }

    private static void requireId(String id) {
        try {
            if (id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Keep a single validation response.
        }
        throw invalid("A valid assistant, version or material identifier is required.");
    }

    private static AssistantApiException notFound() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Private assistant or version not found.");
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException conflict(String detail) {
        return new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT", detail);
    }

    /**
     * A submitted version with optional scoped knowledge and local tool selection.
     *
     * @param name assistant label
     * @param instructions owner-authored instructions
     * @param resourceIds ordered linked text materials
     * @param tools permitted local tool identifier
     */
    public record Draft(String name, String instructions, List<String> resourceIds,
        List<String> tools) {

        @Override
        public String toString() {
            return "Draft[protected]";
        }
    }

    /**
     * Full current assistant record.
     *
     * @param id assistant identifier
     * @param projectId fixed owned project, if linked
     * @param status active or requires_review
     * @param head current immutable version
     * @param createdAt original creation time
     * @param updatedAt last head change
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Assistant(String id, String projectId, String status, Version head,
        Instant createdAt,
        Instant updatedAt) {
    }

    /**
     * Bounded private catalog entry.
     *
     * @param id assistant identifier
     * @param name current version label
     * @param projectId fixed project if linked
     * @param headVersionId current version identifier
     * @param headVersionNumber current version number
     * @param status active or requires_review
     * @param createdAt original creation time
     * @param updatedAt last head change
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String id, String name, String projectId, String headVersionId,
        int headVersionNumber, String status, Instant createdAt, Instant updatedAt) {

        @Override
        public String toString() {
            return "Summary[protected]";
        }
    }

    /**
     * Immutable owner definition and ordered resource references.
     *
     * @param id version identifier
     * @param assistantId owning assistant identifier
     * @param number monotonically increasing number
     * @param name version label
     * @param instructions authored instructions
     * @param resourceIds scope-checked material identities when used
     * @param tools selected local tool identities
     * @param createdAt creation time
     * @param restoredFromVersionId chosen ancestor for a rollback
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Version(String id, String assistantId, int number, String name,
        String instructions,
        List<String> resourceIds, List<String> tools, Instant createdAt,
        String restoredFromVersionId) {

        @Override
        public String toString() {
            return "Version[protected]";
        }
    }

    /**
     * A bounded catalog or history response.
     *
     * @param items scoped private records
     * @param nextCursor explicit null for the complete bounded list
     * @param <T> response item
     */
    public record Page<T>(List<T> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    /**
     * Verifiable immutable identifiers captured when accepting a run.
     *
     * @param assistantId selected assistant
     * @param versionId immutable version selected
     */
    public record Binding(String assistantId, String versionId) {
    }

    private record Stored(String id, String projectId, String headVersionId, long headEpoch,
        String requestDigest, Instant createdAt, Instant updatedAt, int versionNo, String name) {
    }

    private record StoredVersion(String id, String assistantId, int number, String name,
        String instructions,
        String tool, String restoredFromVersionId, Instant createdAt) {
    }

    private record Definition(String name, String instructions, List<String> resourceIds,
        String tool) {
    }
}
