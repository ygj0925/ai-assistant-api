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

package top.continew.admin.assistant.research;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.retrieval.AssistantRetrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Tests approval fencing before stored evidence is touched.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantResearchTest {

    private static final String ACCOUNT = "b97da80d-ac02-44f0-b15a-61065693b213";
    private static final String WORKSPACE = "ba3be636-86ab-41c0-a211-73eff700b7c7";
    private static final String TASK = "0a02bc75-8365-454f-9c70-18d81c4c58fb";
    private static final String SOURCE = "6b77499b-5fc2-40a3-abda-ab2b8d78c4a6";
    private static final User USER = new User(ACCOUNT, "qa", WORKSPACE, null);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
    private final AssistantRetrieval retrieval = mock(AssistantRetrieval.class);
    private final AssistantErasure erasure = mock(AssistantErasure.class);
    private final AssistantResearch research = new AssistantResearch(jdbc,
        mock(AssistantAccounts.class), retrieval, journal, erasure, 0);

    @BeforeEach
    void workspaceAndJournalAreReady() {
        when(journal.enabled()).thenReturn(true);
        when(journal.acceptsErasure()).thenReturn(true);
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(ACCOUNT), eq(WORKSPACE))).thenReturn(List.of(WORKSPACE));
    }

    @Test
    void oldRevisionCannotApproveOrCancelEvenWithCurrentOwnership() throws SQLException {
        task("awaiting_approval", 2, 0);
        rejectsWith(() -> research.approve(USER, TASK, 1), HttpStatus.CONFLICT);
        rejectsWith(() -> research.cancel(USER, TASK, 1), HttpStatus.CONFLICT);
        verifyNoInteractions(retrieval, erasure);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void olderCompletedApprovalCannotDownloadPastIndependentCutoff() throws SQLException {
        task("completed", 1, 125);
        when(journal.researchCutoff(ACCOUNT, WORKSPACE, TASK)).thenReturn(125L);
        rejectsWith(() -> research.report(USER, TASK, null), HttpStatus.CONFLICT);
        verifyNoInteractions(retrieval);
        verify(jdbc, never()).queryForObject(contains("SELECT question"), eq(String.class),
            any(Object[].class));
    }

    @Test
    void malformedScopePayloadAndLostMembershipNeverCreatePlan() {
        rejectsWith(() -> research.create(USER, "valid-key", "test", List.of(SOURCE, SOURCE)),
            HttpStatus.BAD_REQUEST);
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(ACCOUNT), eq(WORKSPACE))).thenReturn(List.of());
        rejectsWith(() -> research.create(USER, "valid-key", "test", List.of(SOURCE)),
            HttpStatus.FORBIDDEN);
        verifyNoInteractions(retrieval, erasure);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private void task(String state, int revision, long epoch) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(TASK);
        when(row.getString("account_id")).thenReturn(ACCOUNT);
        when(row.getString("workspace_id")).thenReturn(WORKSPACE);
        when(row.getString("state")).thenReturn(state);
        when(row.getInt("revision")).thenReturn(revision);
        when(row.getLong("approval_epoch")).thenReturn(epoch);
        when(jdbc.query(contains("FROM assistant_research_task"),
            ArgumentMatchers.<RowMapper<Object>>any(), eq(TASK), eq(ACCOUNT), eq(WORKSPACE)))
            .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(row, 0)));
    }

    private static void rejectsWith(Runnable operation, HttpStatus expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(AssistantApiException.class,
            error -> org.assertj.core.api.Assertions.assertThat(error.status())
                .isEqualTo(expected));
    }
}
