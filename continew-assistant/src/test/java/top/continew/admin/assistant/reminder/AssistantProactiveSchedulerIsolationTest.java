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

package top.continew.admin.assistant.reminder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Verifies scope-local failure backoff and its independent privacy fence.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantProactiveSchedulerIsolationTest {

    private static final Instant NOW = Instant.parse("2026-11-24T08:00:00Z");
    private static final long DUE = NOW.minusSeconds(1).toEpochMilli();
    private static final long EPOCH = NOW.minusSeconds(2).toEpochMilli();
    private static final String BAD = "failing-account";
    private static final String GOOD = "healthy-account";
    private static final String WORKSPACE = "workspace";
    private static final String MEMBER = "current-membership";
    private static final String SOURCE = "authorized-calendar";
    private static final String BACKOFF = "AND next_check_at = ?";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AssistantReminders reminders = mock(AssistantReminders.class);
    private final AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
    private final AssistantErasure erasure = mock(AssistantErasure.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    private AssistantProactiveAssistant assistant;

    @BeforeEach
    void prepare() throws SQLException {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        assistant = new AssistantProactiveAssistant(jdbc, mock(AssistantAccounts.class), reminders,
            journal, erasure, manager, environment, true);
        when(reminders.available()).thenReturn(true);
        when(journal.acceptsErasure()).thenReturn(true);
        when(manager.getTransaction(any())).thenAnswer(call -> mock(TransactionStatus.class));
        ResultSet first = due(BAD);
        ResultSet second = due(GOOD);
        when(jdbc.query(contains("FROM assistant_proactive_preference p"),
            ArgumentMatchers.<RowMapper<Object>>any(), eq(NOW.toEpochMilli())))
            .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(first, 0),
                call.<RowMapper<Object>>getArgument(1).mapRow(second, 1)));
        scope(BAD, EPOCH);
        scope(GOOD, EPOCH);
        ResultSet source = mock(ResultSet.class);
        when(source.getString("reminder_id")).thenReturn(SOURCE);
        when(source.getLong("config_epoch")).thenReturn(1L);
        when(source.getLong("slot_day")).thenReturn(21_500L);
        when(jdbc.query(contains("r.next_wake_at <= ?"), ArgumentMatchers.<RowMapper<Object>>any(),
            eq(GOOD), eq(WORKSPACE), eq(NOW.toEpochMilli()),
            eq(NOW.plusSeconds(86_400).toEpochMilli())))
            .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(source, 0)));
    }

    @Test
    void defersOnlyTheFailedScopeAndStillPublishesAHealthySuggestion() {
        when(jdbc.query(contains("r.next_wake_at <= ?"), ArgumentMatchers.<RowMapper<Object>>any(),
            eq(BAD), eq(WORKSPACE), eq(NOW.toEpochMilli()),
            eq(NOW.plusSeconds(86_400).toEpochMilli())))
            .thenThrow(new DataAccessResourceFailureException("synthetic scope failure"));
        assistant.advance(NOW);
        verify(jdbc).update(contains(BACKOFF), eq(NOW.plusSeconds(60).toEpochMilli()),
            eq(BAD), eq(WORKSPACE), eq(MEMBER), eq(EPOCH), eq(DUE));
        verify(erasure).reserveProactiveSuggestion(argThat(owner -> owner.id().equals(GOOD)
            && owner.workspaceId().equals(WORKSPACE)), any());
    }

    @Test
    void changedPolicyCannotReceiveBackoffFromItsOlderTicket() throws SQLException {
        ResultSet oldPolicy = policy(EPOCH);
        ResultSet newPolicy = policy(EPOCH + 1);
        when(jdbc.query(contains("FROM assistant_proactive_preference WHERE"),
            ArgumentMatchers.<RowMapper<Object>>any(), eq(BAD), eq(WORKSPACE)))
            .thenAnswer(
                call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(oldPolicy, 0)))
            .thenAnswer(
                call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(newPolicy, 0)));
        when(jdbc.query(contains("r.next_wake_at <= ?"), ArgumentMatchers.<RowMapper<Object>>any(),
            eq(BAD), eq(WORKSPACE), eq(NOW.toEpochMilli()),
            eq(NOW.plusSeconds(86_400).toEpochMilli())))
            .thenThrow(new DataAccessResourceFailureException("synthetic scope failure"));
        assistant.advance(NOW);
        verify(jdbc, never()).update(contains(BACKOFF), eq(NOW.plusSeconds(60).toEpochMilli()),
            eq(BAD), eq(WORKSPACE), eq(MEMBER), eq(EPOCH), eq(DUE));
        verify(erasure).reserveProactiveSuggestion(argThat(owner -> owner.id().equals(GOOD)),
            any());
    }

    @Test
    void pendingJournalStopsBackoffAndFurtherCandidates() {
        when(jdbc.query(contains("r.next_wake_at <= ?"), ArgumentMatchers.<RowMapper<Object>>any(),
            eq(BAD), eq(WORKSPACE), eq(NOW.toEpochMilli()),
            eq(NOW.plusSeconds(86_400).toEpochMilli())))
            .thenAnswer(call -> {
                when(journal.dispatchPaused()).thenReturn(true);
                throw new DataAccessResourceFailureException(
                    "scope failure with pending privacy replay");
            });
        assistant.advance(NOW);
        verify(jdbc, never()).update(contains(BACKOFF), eq(NOW.plusSeconds(60).toEpochMilli()),
            eq(BAD), eq(WORKSPACE), eq(MEMBER), eq(EPOCH), eq(DUE));
        verify(jdbc, never()).queryForList(contains("FROM assistant_account"), eq(String.class),
            eq(GOOD));
        verifyNoInteractions(erasure);
    }

    private void scope(String account, long epoch) throws SQLException {
        ResultSet row = policy(epoch);
        ResultSet member = mock(ResultSet.class);
        when(member.getString("membership_revision")).thenReturn(MEMBER);
        when(jdbc.queryForList(contains("FROM assistant_account"), eq(String.class), eq(account)))
            .thenReturn(List.of(account));
        when(jdbc.query(contains("FROM assistant_workspace_member WHERE"),
            ArgumentMatchers.<RowMapper<Object>>any(), eq(account), eq(WORKSPACE)))
            .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(member, 0)));
        when(jdbc.query(contains("FROM assistant_proactive_preference WHERE"),
            ArgumentMatchers.<RowMapper<Object>>any(), eq(account), eq(WORKSPACE)))
            .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(row, 0)));
    }

    private static ResultSet due(String account) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("account_id")).thenReturn(account);
        when(row.getString("workspace_id")).thenReturn(WORKSPACE);
        when(row.getString("membership_revision")).thenReturn(MEMBER);
        when(row.getLong("policy_epoch")).thenReturn(EPOCH);
        when(row.getLong("next_check_at")).thenReturn(DUE);
        return row;
    }

    private static ResultSet policy(long epoch) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("membership_revision")).thenReturn(MEMBER);
        when(row.getBoolean("enabled")).thenReturn(true);
        when(row.getString("role")).thenReturn("schedule_planner");
        when(row.getString("notification")).thenReturn("in_app");
        when(row.getString("time_zone")).thenReturn("UTC");
        when(row.getLong("policy_epoch")).thenReturn(epoch);
        when(row.getObject("next_check_at", Long.class)).thenReturn(DUE);
        return row;
    }
}
