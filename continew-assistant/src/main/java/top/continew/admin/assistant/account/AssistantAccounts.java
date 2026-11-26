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

package top.continew.admin.assistant.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Consumer accounts and revocable device sessions.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantAccounts {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{2,31}");
    private static final Pattern TOKEN = Pattern.compile("(?i:Bearer) (as_[A-Za-z0-9_-]{43})");
    private static final Pattern WORKSPACE_CURSOR =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> THEMES = Set.of("system", "light", "dark");
    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final String ACCOUNT_LOOKUP = """
        SELECT a.id, a.username, a.password_hash, a.locale, a.theme, w.id AS workspace_id
        FROM assistant_account a JOIN assistant_workspace w ON w.personal_owner_id = a.id
        """;
    private final JdbcTemplate jdbc;
    private final boolean registrationEnabled;
    private final AssistantDeletionJournal deletionJournal;
    private final SecureRandom secureRandom = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    /**
     * Creates a consumer account service.
     *
     * @param jdbc product data access
     * @param registrationEnabled whether public registration is enabled
     * @param deletionJournal durable privacy deletion gate
     */
    public AssistantAccounts(JdbcTemplate jdbc,
        @Value("${assistant.auth.registration-enabled:false}") boolean registrationEnabled,
        AssistantDeletionJournal deletionJournal) {
        this.jdbc = jdbc;
        this.registrationEnabled = registrationEnabled;
        this.deletionJournal = deletionJournal;
    }

    /**
     * Registers an account, its personal workspace and the first session atomically.
     *
     * @param credentials new account credentials
     * @return initial session
     */
    @Transactional
    public Session register(Credentials credentials) {
        if (!registrationEnabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Registration is closed.");
        }
        String username = validate(credentials, true);
        String accountId = UUID.randomUUID().toString();
        if (deletionJournal.blocksAccount(accountId)) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "Consumer registration is temporarily unavailable.");
        }
        String workspaceId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Preferences preferences = new Preferences("zh-CN", "system");
        try {
            jdbc.update("""
                INSERT INTO assistant_account
                    (id, username, password_hash, locale, theme, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, accountId, username, encoder.encode(credentials.password()),
                preferences.locale(),
                preferences.theme(), now);
        } catch (DuplicateKeyException ignored) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
                "Username is unavailable.");
        }
        jdbc.update(
            "INSERT INTO assistant_workspace (id, personal_owner_id, created_at) VALUES (?, ?, ?)",
            workspaceId, accountId, now);
        jdbc.update("""
            INSERT INTO assistant_workspace_member
                (workspace_id, account_id, membership_role, membership_revision)
            VALUES (?, ?, 'OWNER', ?)
            """, workspaceId, accountId, UUID.randomUUID().toString());
        return issueSession(new User(accountId, username, workspaceId, preferences));
    }

    /**
     * Authenticates a consumer account and issues a new device session.
     *
     * @param credentials consumer account credentials
     * @return new session
     */
    @Transactional
    public Session login(Credentials credentials) {
        String username = validate(credentials, false);
        Account account =
            jdbc.query(ACCOUNT_LOOKUP + " WHERE a.username = ? AND a.deleted_at IS NULL",
                (row, index) -> new Account(readUser(row), row.getString("password_hash")),
                username).stream().findFirst().orElse(null);
        String hash = account == null ? dummyHash : account.passwordHash();
        if (!encoder.matches(credentials.password(), hash) || account == null) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Username or password is incorrect.");
        }
        lockActive(account.user().id());
        return issueSession(account.user());
    }

    /**
     * Resolves the consumer token and checks membership in the requested workspace.
     *
     * @param authorization consumer bearer header
     * @param workspaceId requested workspace or null
     * @return authenticated consumer and device
     */
    public Principal authenticate(String authorization, String workspaceId) {
        if (authorization == null) {
            throw unauthenticated();
        }
        var matcher = TOKEN.matcher(authorization);
        if (!matcher.matches()) {
            throw unauthenticated();
        }
        Principal principal = jdbc.query("""
            SELECT s.id AS session_id, a.id, a.username, a.locale, a.theme, w.id AS workspace_id
            FROM assistant_session s
            JOIN assistant_account a ON a.id = s.account_id
            JOIN assistant_workspace w ON w.personal_owner_id = a.id
            WHERE s.token_digest = ? AND s.expires_at > ? AND a.deleted_at IS NULL
            """, (row, index) -> new Principal(readUser(row), row.getString("session_id")),
            digest(matcher.group(1)), System.currentTimeMillis()).stream().findFirst()
            .orElseThrow(AssistantAccounts::unauthenticated);
        if (deletionJournal.blocksAccount(principal.user().id())) {
            throw unauthenticated();
        }
        if (workspaceId == null) {
            workspaceId = principal.user().workspaceId();
        }
        List<String> memberships = jdbc.queryForList(
            """
                SELECT membership_revision FROM assistant_workspace_member
                WHERE account_id = ? AND workspace_id = ?
                """,
            String.class, principal.user().id(), workspaceId);
        if (memberships.isEmpty() || !allowsMembership(principal.user().id(), workspaceId,
            memberships.get(0))) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Workspace is unavailable.");
        }
        User user = principal.user();
        return new Principal(new User(user.id(), user.username(), workspaceId, user.preferences()),
            principal.sessionId());
    }

    /**
     * Confirms a workspace membership read from the database has not already departed.
     *
     * @param accountId workspace member
     * @param workspaceId selected workspace
     * @param revision observed membership generation
     * @return whether the observed generation can authorize work
     */
    public boolean allowsMembership(String accountId, String workspaceId, String revision) {
        return !deletionJournal.blocksWorkspaceMembership(accountId, workspaceId, revision);
    }

    /**
     * Lists active workspace memberships for an authenticated consumer.
     *
     * @param principal authenticated consumer identity
     * @param cursor prior page continuation or null
     * @param limit page size
     * @return workspace memberships without other account details
     */
    public WorkspacePage workspaces(Principal principal, String cursor, int limit) {
        if (limit < 1 || limit > 100
            || cursor != null && !WORKSPACE_CURSOR.matcher(cursor).matches()) {
            throw validation("Invalid workspace page request.");
        }
        User user = principal.user();
        List<WorkspaceSummary> rows = jdbc.query(
            """
                SELECT w.id, CASE WHEN w.personal_owner_id = m.account_id THEN 'personal' ELSE 'workspace' END AS kind,
                    CASE WHEN o.owner_account_id = m.account_id THEN FALSE END AS can_leave
                FROM assistant_workspace_member m JOIN assistant_workspace w ON w.id = m.workspace_id
                LEFT JOIN assistant_organization o ON o.workspace_id = w.id
                WHERE m.account_id = ? AND w.id > ? ORDER BY w.id LIMIT ?
                """,
            (row, index) -> new WorkspaceSummary(row.getString("id"), row.getString("kind"),
                row.getObject("can_leave", Boolean.class)),
            user.id(), cursor == null ? "" : cursor, limit + 1);
        if (rows.size() <= limit) {
            return new WorkspacePage(List.copyOf(rows), null);
        }
        return new WorkspacePage(List.copyOf(rows.subList(0, limit)), rows.get(limit - 1).id());
    }

    /**
     * Updates the current account's profile preferences.
     *
     * @param principal authenticated consumer and device
     * @param patch settings to update
     * @return updated profile
     */
    @Transactional
    public User updatePreferences(Principal principal, Preferences patch) {
        String locale = normalizeLocale(patch.locale());
        String theme = patch.theme();
        if (theme != null && !THEMES.contains(theme)) {
            throw validation("Theme must be system, light or dark.");
        }
        String id = principal.user().id();
        lockActive(id);
        jdbc.update(
            "UPDATE assistant_account SET locale = COALESCE(?, locale), theme = COALESCE(?, theme) WHERE id = ?",
            locale, theme, id);
        Preferences result = jdbc.queryForObject(
            "SELECT locale, theme FROM assistant_account WHERE id = ?",
            (row, index) -> new Preferences(row.getString("locale"), row.getString("theme")), id);
        return new User(id, principal.user().username(), principal.user().workspaceId(), result);
    }

    /**
     * Lists unexpired sessions belonging to this consumer account.
     *
     * @param principal authenticated consumer and device
     * @return active devices
     */
    public List<Device> devices(Principal principal) {
        return jdbc.query("""
            SELECT id, created_at, expires_at FROM assistant_session
            WHERE account_id = ? AND expires_at > ? ORDER BY created_at DESC, id
            """,
            (row, index) -> new Device(row.getString("id"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("expires_at")),
                principal.sessionId().equals(row.getString("id"))),
            principal.user().id(), System.currentTimeMillis());
    }

    /**
     * Revokes a session owned by the consumer account.
     *
     * @param principal authenticated consumer and device
     * @param id device session ID
     */
    public void revoke(Principal principal, String id) {
        if (jdbc.update("DELETE FROM assistant_session WHERE account_id = ? AND id = ?",
            principal.user().id(), id) == 0) {
            throw new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Session not found.");
        }
    }

    /**
     * Locks the account before creating owned data or issuing a session.
     *
     * @param accountId authenticated account identifier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActive(String accountId) {
        if (jdbc.queryForList(
            "SELECT id FROM assistant_account WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            accountId).isEmpty() || deletionJournal.blocksAccount(accountId)) {
            throw unauthenticated();
        }
    }

    /**
     * Verifies current credentials and session while holding the account lock for erasure.
     *
     * @param principal current authenticated session
     * @param password current account password
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requirePrivacyPassword(Principal principal, String password) {
        if (password == null || password.isEmpty() || password.indexOf(0) >= 0
            || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw validation("The current password is required.");
        }
        String accountId = principal.user().id();
        lockActive(accountId);
        String hash = jdbc.query("""
            SELECT a.password_hash FROM assistant_account a
            JOIN assistant_session s ON s.account_id = a.id
            WHERE a.id = ? AND s.id = ? AND s.expires_at > ? FOR UPDATE
            """, (row, index) -> row.getString("password_hash"), accountId, principal.sessionId(),
            System.currentTimeMillis()).stream().findFirst()
            .orElseThrow(AssistantAccounts::unauthenticated);
        if (!encoder.matches(password, hash)) {
            throw new AssistantApiException(HttpStatus.UNAUTHORIZED, "INVALID_CURRENT_PASSWORD",
                "The current password could not be verified.");
        }
    }

    private Session issueSession(User user) {
        long now = System.currentTimeMillis();
        Instant expires = Instant.ofEpochMilli(now).plus(SESSION_TTL);
        byte[] entropy = new byte[32];
        secureRandom.nextBytes(entropy);
        String token = "as_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        jdbc.update("DELETE FROM assistant_session WHERE account_id = ? AND expires_at <= ?",
            user.id(), now);
        jdbc.update("""
            INSERT INTO assistant_session (id, account_id, token_digest, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), user.id(), digest(token), now,
            expires.toEpochMilli());
        return new Session(token, expires, user);
    }

    private static String validate(Credentials credentials, boolean registering) {
        String username = credentials.username();
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw validation("Username must be 3 to 32 letters, digits, underscores or hyphens.");
        }
        String password = credentials.password();
        int minimum = registering ? 12 : 1;
        boolean hasMinimum =
            password != null && password.codePointCount(0, password.length()) >= minimum;
        if (!hasMinimum || password.getBytes(StandardCharsets.UTF_8).length > 72
            || password.indexOf(0) >= 0) {
            throw validation("Password length is invalid.");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private static String normalizeLocale(String locale) {
        if (locale == null) {
            return null;
        }
        try {
            Locale supported = new Locale.Builder().setLanguageTag(locale).build();
            if (locale.length() > 35 || supported.getLanguage().isEmpty()) {
                throw validation("Locale must be a valid language tag.");
            }
            return supported.toLanguageTag();
        } catch (IllformedLocaleException ignored) {
            throw validation("Locale must be a valid language tag.");
        }
    }

    private static User readUser(ResultSet row) throws SQLException {
        return new User(row.getString("id"), row.getString("username"),
            row.getString("workspace_id"),
            new Preferences(row.getString("locale"), row.getString("theme")));
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static AssistantApiException validation(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AssistantApiException unauthenticated() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
            "Sign in to continue.");
    }

    /**
     * Account credentials.
     *
     * @param username consumer username
     * @param password plaintext password received over the protected channel
     */
    public record Credentials(String username, String password) {
    }

    /**
     * Locale and appearance settings.
     *
     * @param locale language tag
     * @param theme appearance setting
     */
    public record Preferences(String locale, String theme) {
    }

    /**
     * Consumer profile and selected workspace.
     *
     * @param id consumer account ID
     * @param username normalized username
     * @param workspaceId selected workspace
     * @param preferences account settings
     */
    public record User(String id, String username, String workspaceId, Preferences preferences) {
    }

    /**
     * An active membership available to this consumer.
     *
     * @param id workspace identifier
     * @param kind personal workspace of this consumer or another eligible workspace
     * @param canLeave optional false when the designated organization owner cannot leave
     */
    public record WorkspaceSummary(String id, String kind,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canLeave) {
    }

    /**
     * A page of active memberships.
     *
     * @param items authorized workspaces
     * @param nextCursor continuation or null at the end
     */
    public record WorkspacePage(List<WorkspaceSummary> items, String nextCursor) {
    }

    /**
     * Raw token, returned only on issue, plus session expiry and consumer profile.
     *
     * @param accessToken bearer token
     * @param expiresAt session expiry
     * @param user consumer profile
     */
    public record Session(String accessToken, Instant expiresAt, User user) {
    }

    /**
     * Authenticated account and device session ID.
     *
     * @param user consumer profile
     * @param sessionId device session ID
     */
    public record Principal(User user, String sessionId) {
    }

    /**
     * Device session metadata without credentials.
     *
     * @param id session ID
     * @param createdAt issue time
     * @param expiresAt expiry time
     * @param current whether this is the caller's session
     */
    public record Device(String id, Instant createdAt, Instant expiresAt, boolean current) {
    }

    private record Account(User user, String passwordHash) {
    }
}
