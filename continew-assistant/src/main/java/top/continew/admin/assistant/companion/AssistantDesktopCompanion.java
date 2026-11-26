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

package top.continew.admin.assistant.companion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.Actor;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Relays one reviewed local syntax task between two live sessions of its personal workspace owner.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantDesktopCompanion {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private final JdbcTemplate jdbc;
    private final AssistantAccounts accounts;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final DesktopCompanionBroker broker;

    /**
     * Activates the local relay only for an explicitly bound development or test instance.
     *
     * @param jdbc consumer data source
     * @param accounts current authenticated account authorities
     * @param manager transaction manager
     * @param environment runtime profiles
     * @param bind listener address
     * @param enabled explicit feature flag
     * @param taskSeconds bounded local task lifetime
     */
    public AssistantDesktopCompanion(JdbcTemplate jdbc, AssistantAccounts accounts,
        PlatformTransactionManager manager,
        Environment environment, @Value("${server.address:}") String bind,
        @Value("${assistant.companion.local.enabled:false}") boolean enabled,
        @Value("${assistant.companion.local.task-seconds:90}") int taskSeconds) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.enabled = enabled;
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (enabled && (!LOOPBACK.contains(bind) || profiles.contains("prod")
            || profiles.contains("production")
            || !profiles.contains("dev") && !profiles.contains("test"))) {
            throw new IllegalStateException(
                "Local desktop companion requires a loopback dev/test instance.");
        }
        if (enabled && (taskSeconds < 12 || taskSeconds > 120)) {
            throw new IllegalStateException(
                "Local desktop companion task lifetime must be 12–120 seconds.");
        }
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        broker = new DesktopCompanionBroker(Clock.systemUTC(), this::current, Duration.ofMinutes(2),
            Duration.ofMinutes(8), Duration.ofSeconds(taskSeconds), Duration.ofSeconds(18));
    }

    /**
     * Reports whether the current personal workspace can offer a local companion session.
     *
     * @param user signed-in account projection
     * @return availability for the current workspace
     */
    public boolean available(User user) {
        if (!enabled || user == null) {
            return false;
        }
        try {
            return jdbc
                .queryForList(
                    """
                        SELECT m.membership_revision FROM assistant_workspace_member m
                        JOIN assistant_workspace w ON w.id = m.workspace_id AND w.personal_owner_id = m.account_id
                        WHERE m.account_id = ? AND m.workspace_id = ?
                        """,
                    String.class, user.id(), user.workspaceId())
                .stream()
                .anyMatch((revision) -> accounts.allowsMembership(user.id(), user.workspaceId(),
                    revision));
        } catch (DataAccessException failure) {
            return false;
        }
    }

    static boolean loopback(String address) {
        return address != null && LOOPBACK.contains(address);
    }

    <T> T use(Principal principal, BiFunction<DesktopCompanionBroker, Actor, T> operation) {
        if (!enabled) {
            throw unavailable();
        }
        // Enter the broker before taking any account/session row locks. Two of the same owner's sessions
        // can then safely serialize an approval with logout and revoke, without opposing row-lock order.
        synchronized (broker) {
            try {
                return transactions.execute((transaction) -> {
                    User user = principal.user();
                    accounts.lockActive(user.id());
                    String membership =
                        lockedMembership(user.id(), user.workspaceId(), principal.sessionId());
                    if (membership == null) {
                        throw unavailable();
                    }
                    Actor actor =
                        new Actor(user.id(), user.workspaceId(), principal.sessionId(), membership);
                    return operation.apply(broker, actor);
                });
            } catch (DataAccessException | TransactionException failure) {
                broker.clearAuthorities();
                throw failure;
            }
        }
    }

    private boolean current(Actor scope) {
        return scope.membership()
            .equals(lockedMembership(scope.account(), scope.workspace(), scope.session()));
    }

    private String lockedMembership(String account, String workspace, String session) {
        return jdbc.queryForList("""
            SELECT m.membership_revision FROM assistant_session s
            JOIN assistant_account a ON a.id = s.account_id AND a.deleted_at IS NULL
            JOIN assistant_workspace w ON w.personal_owner_id = a.id
            JOIN assistant_workspace_member m ON m.workspace_id = w.id AND m.account_id = a.id
            WHERE a.id = ? AND w.id = ? AND s.id = ? AND s.expires_at > ? FOR UPDATE
            """, String.class, account, workspace, session, System.currentTimeMillis()).stream()
            .filter((revision) -> accounts.allowsMembership(account, workspace, revision))
            .findFirst().orElse(null);
    }

    static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Local desktop companion unavailable.");
    }
}
