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

package top.continew.admin.assistant.retrieval;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stores and resolves text evidence from the deployment's approved source catalog.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = {"assistant.enabled", "assistant.retrieval.enabled"},
    havingValue = "true")
public class AssistantRetrieval {

    private static final int MAX_QUERY_POINTS = 256;
    private static final int MAX_TERMS = 12;
    private static final int MAX_CATALOG_SOURCES = 1024;
    private static final int MAX_CITATIONS = 6;
    private static final int REQUESTS_PER_MINUTE = 60;
    private static final String AVAILABLE = "available";
    private static final String STALE = "stale";
    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern TERM_SEPARATOR = Pattern.compile("[\\p{P}\\p{Z}\\s]+");
    private static final Pattern PUBLIC_HOST =
        Pattern.compile("(?:[a-z0-9-]+\\.)+[a-z][a-z0-9-]*", Pattern.CASE_INSENSITIVE);
    private static final String MEMBER_CHECK = """
        SELECT workspace_id FROM assistant_workspace_member
        WHERE workspace_id = ? AND account_id = ?
        """;

    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final AssistantDeletionJournal deletions;

    /**
     * Creates the retrieval service.
     *
     * @param jdbc product data access
     * @param accounts account lifecycle coordination
     * @param deletions recoverable erasure gate
     */
    public AssistantRetrieval(JdbcTemplate jdbc, AssistantAccounts accounts,
        AssistantDeletionJournal deletions) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.deletions = deletions;
    }

    /**
     * Creates a private answer with citations to matching approved evidence.
     *
     * @param user consumer with a selected workspace
     * @param query search text
     * @return persisted retrieval answer
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Answer create(User user, String query) {
        List<String> terms = queryTerms(query);
        accounts.lockActive(user.id());
        if (jdbc.queryForList(MEMBER_CHECK + " FOR UPDATE", String.class, user.workspaceId(),
            user.id()).isEmpty()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Workspace is unavailable.");
        }

        Instant now = Instant.now();
        Integer recent = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assistant_retrieval_answer
            WHERE account_id = ? AND created_at >= ?
            """, Integer.class, user.id(), now.minusSeconds(60).toEpochMilli());
        if (recent != null && recent >= REQUESTS_PER_MINUTE) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many retrieval requests.");
        }

        List<Source> sources = search(terms);
        String answerId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO assistant_retrieval_answer(id, workspace_id, account_id, created_at)
            VALUES (?, ?, ?, ?)
            """, answerId, user.workspaceId(), user.id(), now.toEpochMilli());
        for (int position = 0; position < sources.size(); position++) {
            Source source = sources.get(position);
            Evidence evidence = source.evidence();
            jdbc.update("""
                INSERT INTO assistant_retrieval_citation(id, answer_id, source_id, ordinal,
                    title, source_url, claim_text, evidence_text, accessed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), answerId, source.id(), position,
                evidence.title(), evidence.url(), evidence.claim(), evidence.excerpt(),
                source.accessedAt().toEpochMilli());
        }
        return load(user, answerId);
    }

    /**
     * Reads private citations and checks each source's current availability.
     *
     * @param user consumer with a selected workspace
     * @param answerId answer identifier
     * @return current evidence-backed view
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Answer get(User user, String answerId) {
        return load(user, answerId);
    }

    /**
     * Lists eligible text-only sources for an explicit local research scope.
     *
     * @param after previously seen source identifier, or the empty string
     * @param limit maximum results
     * @return ordered source metadata
     */
    public List<ResearchSource> researchCatalog(String after, int limit) {
        long now = System.currentTimeMillis();
        List<ResearchSource> sources = jdbc.query("""
            SELECT id, title, source_url, accessed_at
            FROM assistant_retrieval_source
            WHERE available = TRUE AND accessed_at <= ? AND expires_at > ? AND id > ?
            ORDER BY id LIMIT ?
            """, (row, index) -> new ResearchSource(row.getString("id"), row.getString("title"),
            row.getString("source_url"), Instant.ofEpochMilli(row.getLong("accessed_at"))),
            now, now, after, MAX_CATALOG_SOURCES + 1);
        if (sources.size() > MAX_CATALOG_SOURCES) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                "The approved source catalog exceeds its local capacity.");
        }
        return sources.stream().filter(source -> !source.title().isBlank() && safeUrl(source.url()))
            .limit(limit).toList();
    }

    /**
     * Resolves only the explicitly named, currently eligible local evidence records.
     *
     * @param ids selected curated IDs
     * @return available evidence from the requested set
     */
    public List<ResearchEvidence> researchEvidence(List<String> ids) {
        if (ids.isEmpty() || ids.size() > MAX_CITATIONS) {
            throw invalidQuery();
        }
        long now = System.currentTimeMillis();
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        List<Object> arguments = new ArrayList<>(List.of(now, now));
        arguments.addAll(ids);
        return jdbc.query("""
            SELECT id, title, source_url, claim_text, evidence_text, accessed_at, expires_at
            FROM assistant_retrieval_source
            WHERE available = TRUE AND accessed_at <= ? AND expires_at > ? AND id IN (
            """ + placeholders + ")", AssistantRetrieval::readResearchEvidence,
            arguments.toArray()).stream().filter(Objects::nonNull).toList();
    }

    private static ResearchEvidence readResearchEvidence(ResultSet row, int index)
        throws SQLException {
        Evidence evidence = new Evidence(row.getString("title"), row.getString("source_url"),
            row.getString("claim_text"), row.getString("evidence_text"));
        if (!evidence.valid()) {
            return null;
        }
        var source = new ResearchSource(row.getString("id"), evidence.title(), evidence.url(),
            Instant.ofEpochMilli(row.getLong("accessed_at")));
        return new ResearchEvidence(source, evidence.claim(), evidence.excerpt(),
            Instant.ofEpochMilli(row.getLong("expires_at")));
    }

    private List<Source> search(List<String> terms) {
        // Query terms stay out of JDBC, including when a development SQL driver logs parameters.
        List<CatalogIndex> index = jdbc.query("""
            SELECT id, search_text FROM assistant_retrieval_source ORDER BY id LIMIT ?
            """,
            (row, position) -> new CatalogIndex(row.getString("id"), row.getString("search_text")),
            MAX_CATALOG_SOURCES + 1);
        if (index.size() > MAX_CATALOG_SOURCES) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                "The approved source catalog exceeds its local capacity.");
        }
        List<String> matches = index.stream()
            .map(entry -> new Match(entry.id(), relevance(entry.searchText(), terms)))
            .filter(match -> match.relevance() > 0)
            .sorted(Comparator.comparingLong(Match::relevance).reversed())
            .limit(MAX_CITATIONS).map(Match::id).toList();
        if (matches.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(", ", Collections.nCopies(matches.size(), "?"));
        var sources = jdbc.query("""
            SELECT id, title, source_url, claim_text, evidence_text, accessed_at, search_text
            FROM assistant_retrieval_source WHERE id IN (
            """ + placeholders + ")", (row, position) -> readSource(row, terms), matches.toArray())
            .stream().filter(Objects::nonNull)
            .collect(Collectors.toMap(Source::id, Function.identity()));
        return matches.stream().map(sources::get).filter(Objects::nonNull).toList();
    }

    private static Source readSource(ResultSet row, List<String> terms) throws SQLException {
        Evidence evidence = new Evidence(row.getString("title"), row.getString("source_url"),
            row.getString("claim_text"), row.getString("evidence_text"));
        if (!evidence.valid() || relevance(row.getString("search_text"), terms) == 0) {
            return null;
        }
        return new Source(row.getString("id"), evidence,
            Instant.ofEpochMilli(row.getLong("accessed_at")));
    }

    private static long relevance(String text, List<String> terms) {
        String normalized = normalize(text);
        return terms.stream().filter(normalized::contains).count();
    }

    private Answer load(User user, String answerId) {
        if (answerId == null || answerId.length() != 36 || deletions.blocksAccount(user.id())) {
            throw missing();
        }
        List<AnswerRow> entries = jdbc.query("""
            SELECT a.created_at, c.id AS citation_id, c.title, c.source_url, c.claim_text,
                c.evidence_text, c.accessed_at, s.id AS current_id, s.title AS current_title,
                s.source_url AS current_url, s.claim_text AS current_claim,
                s.evidence_text AS current_evidence, s.available AS current_available,
                s.accessed_at AS current_accessed_at, s.expires_at AS current_expires_at
            FROM assistant_retrieval_answer a
            JOIN assistant_workspace_member m ON m.workspace_id = a.workspace_id
                AND m.account_id = a.account_id
            LEFT JOIN assistant_retrieval_citation c ON c.answer_id = a.id
            LEFT JOIN assistant_retrieval_source s ON s.id = c.source_id
            WHERE a.id = ? AND a.account_id = ? AND a.workspace_id = ?
            ORDER BY c.ordinal
            """, AssistantRetrieval::readAnswer, answerId, user.id(), user.workspaceId());
        if (entries.isEmpty() || deletions.blocksAccount(user.id())) {
            throw missing();
        }
        return assemble(answerId, entries, Instant.now());
    }

    private static AnswerRow readAnswer(ResultSet row, int index) throws SQLException {
        Evidence snapshot = new Evidence(row.getString("title"), row.getString("source_url"),
            row.getString("claim_text"), row.getString("evidence_text"));
        Evidence current =
            new Evidence(row.getString("current_title"), row.getString("current_url"),
                row.getString("current_claim"), row.getString("current_evidence"));
        return new AnswerRow(Instant.ofEpochMilli(row.getLong("created_at")),
            row.getString("citation_id"), snapshot,
            Instant.ofEpochMilli(row.getLong("accessed_at")),
            row.getString("current_id"), current, row.getBoolean("current_available"),
            Instant.ofEpochMilli(row.getLong("current_accessed_at")),
            Instant.ofEpochMilli(row.getLong("current_expires_at")));
    }

    private static Answer assemble(String id, List<AnswerRow> entries, Instant now) {
        List<Citation> citations = new ArrayList<>();
        var byClaim = new LinkedHashMap<String, List<String>>();
        for (AnswerRow entry : entries) {
            if (entry.citationId() == null || !safeUrl(entry.snapshot().url())) {
                continue;
            }
            String status = entry.status(now);
            boolean available = AVAILABLE.equals(status);
            if (available) {
                byClaim.computeIfAbsent(entry.snapshot().claim(), ignored -> new ArrayList<>())
                    .add(entry.citationId());
            }
            citations.add(
                new Citation(entry.citationId(), entry.snapshot().title(), entry.snapshot().url(),
                    available ? entry.snapshot().excerpt() : null, entry.accessedAt(), status));
        }
        List<Claim> claims = byClaim.entrySet().stream()
            .map(entry -> new Claim(entry.getKey(), List.copyOf(entry.getValue()))).toList();
        String state = claims.isEmpty() ? "no_sources"
            : citations.stream().anyMatch(citation -> !AVAILABLE.equals(citation.status()))
                ? "partial" : "supported";
        return new Answer(id, state, entries.get(0).createdAt(), claims, List.copyOf(citations));
    }

    private static List<String> queryTerms(String raw) {
        if (raw == null || raw.codePointCount(0, raw.length()) > MAX_QUERY_POINTS
            || raw.codePoints().anyMatch(
                value -> Character.isISOControl(value) && !Character.isWhitespace(value))) {
            throw invalidQuery();
        }
        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            throw invalidQuery();
        }
        List<String> terms =
            TERM_SEPARATOR.splitAsStream(normalized).filter(term -> !term.isBlank())
                .distinct().limit(MAX_TERMS).toList();
        return terms.isEmpty() ? List.of(normalized) : terms;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean safeUrl(String raw) {
        if (raw == null || raw.length() > 2048) {
            return false;
        }
        try {
            URI url = URI.create(raw);
            String host = url.getHost();
            return "https".equalsIgnoreCase(url.getScheme()) && url.getRawUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443)
                && host != null && PUBLIC_HOST.matcher(host).matches();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static AssistantApiException invalidQuery() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Enter a search query of up to 256 characters.");
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Retrieval answer not found.");
    }

    /**
     * A private response grounded by its current citations.
     *
     * @param id answer identifier
     * @param state supported, partial or no sources
     * @param createdAt answer creation time
     * @param claims currently supported claims
     * @param citations ordered citation records
     */
    public record Answer(String id, String state, Instant createdAt, List<Claim> claims,
        List<Citation> citations) {
    }

    /**
     * A catalog claim and the sources that still support it.
     *
     * @param text reviewed claim text
     * @param citationIds identifiers of current supporting references
     */
    public record Claim(String text, List<String> citationIds) {
    }

    /**
     * Recorded source metadata with current source state.
     *
     * @param id reference identifier
     * @param title snapshotted title
     * @param url snapshotted link
     * @param excerpt stored evidence when available
     * @param accessedAt the source's verification time when cited
     * @param status available, stale or unavailable
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(String id, String title, String url, String excerpt, Instant accessedAt,
        String status) {
    }

    /**
     * Reviewable metadata for one eligible curated source.
     *
     * @param id catalogue ID
     * @param title reviewed title
     * @param url approved reference URL
     * @param accessedAt verification instant
     */
    public record ResearchSource(String id, String title, String url, Instant accessedAt) {
    }

    /**
     * Current catalogue evidence used by the internal research report renderer.
     *
     * @param source reviewed source metadata
     * @param claim reviewed factual assertion
     * @param excerpt supporting excerpt
     * @param expiresAt freshness cutoff
     */
    public record ResearchEvidence(ResearchSource source, String claim, String excerpt,
        Instant expiresAt) {
    }

    private record Source(String id, Evidence evidence, Instant accessedAt) {
    }

    private record CatalogIndex(String id, String searchText) {
    }

    private record Match(String id, long relevance) {
    }

    private record Evidence(String title, String url, String claim, String excerpt) {

        boolean valid() {
            return title != null && !title.isBlank() && claim != null && !claim.isBlank()
                && excerpt != null && !excerpt.isBlank() && safeUrl(url);
        }
    }

    private record AnswerRow(Instant createdAt, String citationId, Evidence snapshot,
        Instant accessedAt,
        String currentId, Evidence current, boolean available, Instant currentAccessedAt,
        Instant currentExpiresAt) {

        String status(Instant now) {
            if (currentId == null || !available || !snapshot.equals(current)) {
                return UNAVAILABLE;
            }
            return currentAccessedAt.isAfter(now) || !currentExpiresAt.isAfter(now) ? STALE
                : AVAILABLE;
        }
    }
}
