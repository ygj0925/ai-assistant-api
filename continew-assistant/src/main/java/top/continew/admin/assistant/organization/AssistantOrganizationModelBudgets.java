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

package top.continew.admin.assistant.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.OrganizationBudgetGuard.Marker;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Authorizes and enforces an explicitly reviewed organization model-unit budget.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantOrganizationModelBudgets {

    private static final String DEFAULT_REVISION = "00000000-0000-0000-0000-000000000000";
    private static final long MAX_LIMIT = 1_000_000_000L;
    private static final String UNIT = "output_code_points";
    private static final String TIME_ZONE = "UTC";
    private final JdbcTemplate jdbc;
    private final AssistantOrganizations organizations;
    private final OrganizationBudgetGuard guard;
    private final boolean authoringEnabled;

    /**
     * Enables reviewed local budget management separately from persisted enforcement.
     *
     * @param jdbc organization model ledger
     * @param organizations existing organization owner scope and audit
     * @param guard independent budget watermark
     * @param enabled explicit local authoring decision
     * @param environment active profiles
     */
    public AssistantOrganizationModelBudgets(JdbcTemplate jdbc,
        AssistantOrganizations organizations,
        OrganizationBudgetGuard guard,
        @Value("${assistant.organization-model-budget.enabled:false}") boolean enabled,
        Environment environment) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.guard = guard;
        authoringEnabled = enabled && environment.matchesProfiles("(dev | test) & !prod");
    }

    /**
     * Shows the budget editor to the designated scoped owner when authoring is ready.
     *
     * @param user authenticated consumer
     * @return whether the budget editor may be used
     */
    public boolean visibleTo(User user) {
        return user != null && authoringEnabled && guard.inspect(user.workspaceId()).ready()
            && organizations.policyOwner(user);
    }

    /**
     * Reads a consistent budget and current-month usage for the designated owner.
     *
     * @param actor authenticated owner and session
     * @return current review revision and aggregate usage
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View read(Principal actor) {
        String workspace = requireOwner(actor);
        return view(workspace, verified(workspace).stored(), Instant.now());
    }

    /**
     * Persists a reviewed cap and advances its independent recovery watermark.
     *
     * @param actor authenticated owner and session
     * @param baseRevision precise revision displayed to the owner
     * @param monthlyLimit null or an integer local output cap
     * @param confirm an explicit confirmation
     * @return current authoritative budget
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View update(Principal actor, String baseRevision, Long monthlyLimit, boolean confirm) {
        String workspace = requireOwner(actor);
        if (!confirm || !OrganizationBudgetGuard.canonicalId(baseRevision)
            || monthlyLimit != null && (monthlyLimit < 0 || monthlyLimit > MAX_LIMIT)) {
            throw invalid();
        }
        State state = verified(workspace);
        Stored prior = state.stored();
        String revision = prior == null ? DEFAULT_REVISION : prior.marker().revision();
        if (!revision.equals(baseRevision)) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "ORGANIZATION_BUDGET_CHANGED",
                "The budget changed. Review its current revision before saving.");
        }
        Instant now = Instant.now();
        if (Objects.equals(monthlyLimit, prior == null ? null : prior.monthlyLimit())) {
            return view(workspace, prior, now);
        }
        var marker = new Marker(newId(), newId());
        var next = new Stored(marker, monthlyLimit, state.identity(), now);
        View summary = view(workspace, next, now);
        if (prior == null) {
            jdbc.update("""
                INSERT INTO assistant_organization_model_budget
                    (workspace_id, revision, monthly_limit_units, guard_id, watermark, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, workspace, marker.revision(), monthlyLimit, next.identity(),
                marker.watermark(),
                now.toEpochMilli());
        } else if (jdbc.update("""
            UPDATE assistant_organization_model_budget
            SET revision = ?, monthly_limit_units = ?, watermark = ?, updated_at = ?
            WHERE workspace_id = ? AND revision = ? AND watermark = ? AND guard_id = ?
            """, marker.revision(), monthlyLimit, marker.watermark(), now.toEpochMilli(),
            workspace, prior.marker().revision(), prior.marker().watermark(),
            prior.identity()) != 1) {
            throw OrganizationBudgetGuard.recoveryRequired();
        }
        organizations.recordModelBudgetChange(actor);
        guard.advance(workspace, prior == null ? null : prior.marker(), marker);
        return summary;
    }

    /**
     * Verifies and covers the new run hold before the charge and run become externally visible.
     * The existing run admission transaction holds the organization row lock.
     *
     * @param workspace current admitted organization or personal workspace
     * @param synthetic whether the resolved transport is the local synthetic model
     * @param createdAt exact millisecond stored on the admitted run
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void protectAdmission(String workspace, boolean synthetic, long createdAt) {
        State state = verified(workspace);
        Stored budget = state == null ? null : state.stored();
        if (budget == null || budget.monthlyLimit() == null) {
            return;
        }
        requireSynthetic(synthetic);
        Totals totals = totals(workspace, Month.at(Instant.ofEpochMilli(createdAt)));
        if (totals.occupied() > budget.monthlyLimit()) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS,
                "ORGANIZATION_BUDGET_EXHAUSTED",
                "The organization has insufficient model units for a new reservation in this UTC month.");
        }
        Marker marker = new Marker(budget.marker().revision(), newId());
        if (jdbc.update("""
            UPDATE assistant_organization_model_budget SET watermark = ?
            WHERE workspace_id = ? AND revision = ? AND watermark = ? AND guard_id = ?
            """, marker.watermark(), workspace, budget.marker().revision(),
            budget.marker().watermark(),
            budget.identity()) != 1) {
            throw OrganizationBudgetGuard.recoveryRequired();
        }
        guard.advance(workspace, budget.marker(), marker);
    }

    /**
     * Prevents a worker from dispatching or publishing against a stale budget ledger.
     * The existing model-policy check holds the organization row lock.
     *
     * @param workspace workspace on the leased run
     * @param synthetic whether the exact leased model and version are synthetic
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrent(String workspace, boolean synthetic) {
        State state = verified(workspace);
        if (state != null && state.stored() != null && state.stored().monthlyLimit() != null) {
            requireSynthetic(synthetic);
        }
    }

    /**
     * Reports whether unmetered temporary synthetic chat is allowed in this selected scope.
     *
     * @param user currently authenticated consumer
     * @return true when this workspace is not subject to a finite model budget
     */
    public boolean temporaryAllowed(User user) {
        if (user == null) {
            return false;
        }
        try {
            return unmetered(user.workspaceId());
        } catch (AssistantApiException | DataAccessException failure) {
            return false;
        }
    }

    /**
     * Denies unmetered temporary synthesis or publication when the selected budget is finite.
     *
     * @param owner actively authenticated workspace consumer
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void requireTemporaryAllowed(User owner) {
        jdbc.queryForList(
            "SELECT workspace_id FROM assistant_organization WHERE workspace_id = ? FOR UPDATE",
            String.class, owner.workspaceId());
        if (!unmetered(owner.workspaceId())) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN,
                "ORGANIZATION_BUDGET_TEMPORARY_UNSUPPORTED",
                "Temporary chat is unavailable when an organization has a finite model-unit budget.");
        }
    }

    private boolean unmetered(String workspace) {
        State state = verified(workspace);
        return state == null || state.stored() == null || state.stored().monthlyLimit() == null;
    }

    private String requireOwner(Principal actor) {
        if (!authoringEnabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Organization model budget authoring is unavailable.");
        }
        organizations.requirePolicyOwner(actor, true);
        String workspace = actor.user().workspaceId();
        if (!guard.inspect(workspace).ready()) {
            throw OrganizationBudgetGuard.recoveryRequired();
        }
        return workspace;
    }

    private State verified(String workspace) {
        RowMapper<Stored> storedRow = (row, index) -> {
            String revision = row.getString("revision");
            if (revision == null) {
                return new Stored(null, null, null, null);
            }
            long limit = row.getLong("monthly_limit_units");
            Long cap = row.wasNull() ? null : limit;
            return new Stored(new Marker(revision, row.getString("watermark")), cap,
                row.getString("guard_id"), Instant.ofEpochMilli(row.getLong("updated_at")));
        };
        var rows = jdbc.query("""
            SELECT b.revision, b.monthly_limit_units, b.guard_id, b.watermark, b.updated_at
            FROM assistant_organization o LEFT JOIN assistant_organization_model_budget b
                ON b.workspace_id = o.workspace_id
            WHERE o.workspace_id = ?
            """, storedRow, workspace);
        var proof = guard.inspect(workspace);
        if (rows.isEmpty()) {
            if (proof.marker() != null) {
                throw OrganizationBudgetGuard.recoveryRequired();
            }
            return null;
        }
        Stored candidate = rows.getFirst();
        Stored stored = candidate.marker() == null ? null : candidate;
        if (proof.configured() && !proof.ready()
            || stored != null
                && (!proof.ready() || !Objects.equals(stored.identity(), proof.identity()))
            || !Objects.equals(stored == null ? null : stored.marker(), proof.marker())) {
            throw OrganizationBudgetGuard.recoveryRequired();
        }
        return new State(stored, proof.identity());
    }

    private View view(String workspace, Stored budget, Instant now) {
        Month month = Month.at(now);
        Totals usage = totals(workspace, month);
        Long cap = budget == null ? null : budget.monthlyLimit();
        Long available = cap == null ? null : Math.max(0L, cap - usage.occupied());
        return new View(budget == null ? DEFAULT_REVISION : budget.marker().revision(),
            cap, usage.reserved(), usage.settled(), available, month.start(), month.endExclusive(),
            TIME_ZONE, UNIT, budget == null ? null : budget.updatedAt());
    }

    private Totals totals(String workspace, Month month) {
        try {
            return jdbc.queryForObject(
                """
                    SELECT COALESCE(SUM(CASE WHEN c.settled_units IS NULL THEN COALESCE(c.reserved_units, 0) ELSE 0 END), 0) AS held,
                        COALESCE(SUM(COALESCE(c.settled_units, 0)), 0) AS spent,
                        COUNT(CASE WHEN c.run_id IS NULL OR c.settled_units > c.reserved_units THEN 1 END) AS invalid
                    FROM assistant_run r LEFT JOIN assistant_run_charge c ON c.run_id = r.id AND c.account_id = r.account_id
                    WHERE r.workspace_id = ? AND r.created_at >= ? AND r.created_at < ?
                    """,
                (row, index) -> {
                    if (row.getLong("invalid") != 0) {
                        throw OrganizationBudgetGuard.recoveryRequired();
                    }
                    return new Totals(row.getBigDecimal("held").longValueExact(),
                        row.getBigDecimal("spent").longValueExact());
                }, workspace, month.start().toEpochMilli(), month.endExclusive().toEpochMilli());
        } catch (ArithmeticException failure) {
            throw OrganizationBudgetGuard.recoveryRequired();
        }
    }

    private static void requireSynthetic(boolean synthetic) {
        if (!synthetic) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN,
                "ORGANIZATION_BUDGET_MODEL_UNSUPPORTED",
                "This local model-unit budget requires the currently configured synthetic model.");
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Review an exact organization budget revision and confirm a nonnegative integer or no cap.");
    }

    private record State(Stored stored, String identity) {
    }

    private record Stored(Marker marker, Long monthlyLimit, String identity, Instant updatedAt) {
    }

    record Month(Instant start, Instant endExclusive) {

        static Month at(Instant instant) {
            YearMonth month = YearMonth.from(instant.atOffset(ZoneOffset.UTC));
            return new Month(month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
    }

    record Totals(long reserved, long settled) {

        Totals {
            if (reserved < 0 || settled < 0) {
                throw OrganizationBudgetGuard.recoveryRequired();
            }
        }

        long occupied() {
            try {
                return Math.addExact(reserved, settled);
            } catch (ArithmeticException failure) {
                throw OrganizationBudgetGuard.recoveryRequired();
            }
        }
    }

    /**
     * Owner-visible local model-unit budget summary with no per-member usage.
     *
     * @param revision exact confirmed budget configuration identifier
     * @param monthlyLimitUnits current monthly cap, or null when uncapped
     * @param reservedUnits local output units held by nonterminal runs
     * @param settledUnits current accepted output units in the same admission month
     * @param availableUnits remaining units or null when uncapped
     * @param monthStart inclusive UTC admission boundary
     * @param monthEndExclusive exclusive UTC admission boundary
     * @param timeZone UTC accounting zone
     * @param unit output code points
     * @param updatedAt last change, or null before the first cap
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(String revision, Long monthlyLimitUnits, long reservedUnits,
        long settledUnits,
        Long availableUnits, Instant monthStart, Instant monthEndExclusive, String timeZone,
        String unit, Instant updatedAt) {
    }
}
