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

package top.continew.admin.assistant.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.chat.AssistantConversationReadAccess;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Tests disabled job routes and workspace revocation before export processing.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantJobAuthorizationTest {

    private static final String JOB = "63459233-ec00-4a55-b2e3-914f8fd79d59";
    private static final String CONVERSATION = "9f89946e-c7c0-42df-b268-f79189451e8f";
    private static final String ACCOUNT = "f45065fc-5b7e-4fe4-a083-b0f1d0f7d039";
    private static final String WORKSPACE = "57f16978-1bc0-4afe-a1f6-eb95bf06e3f6";

    @Test
    void returnsAuthenticationFailureBeforeCheckingDisabledCapability() {
        var accounts = mock(AssistantAccounts.class);
        var controller = new AssistantJobController(accounts, Optional.empty());
        var missingSession = new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
            "An active session is required.");
        when(accounts.authenticate(null, null)).thenThrow(missingSession);

        assertThatThrownBy(() -> controller.list(null, null, null, 25)).isSameAs(missingSession);
        assertThatThrownBy(() -> controller.artifact(null, null, JOB)).isSameAs(missingSession);
    }

    @Test
    void returnsTypedForbiddenForAuthenticatedCallsWhenDisabled() {
        var accounts = mock(AssistantAccounts.class);
        var controller = new AssistantJobController(accounts, Optional.empty());
        String bearer = "Bearer synthetic-test";
        when(accounts.authenticate(bearer, WORKSPACE))
            .thenReturn(new Principal(new User(ACCOUNT, "synthetic", WORKSPACE, null),
                "synthetic-session"));
        List<Runnable> calls = List.of(
            () -> controller.list(bearer, WORKSPACE, null, 25),
            () -> controller.get(bearer, WORKSPACE, JOB),
            () -> controller.cancel(bearer, WORKSPACE, JOB),
            () -> controller.start(bearer, WORKSPACE, CONVERSATION, "fixture-key", null),
            () -> controller.artifact(bearer, WORKSPACE, JOB));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(AssistantApiException.class,
                failure -> {
                    var response = controller.failure(failure);
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(response.getBody().code()).isEqualTo("CAPABILITY_UNAVAILABLE");
                });
        }
    }

    @Test
    void cancelsRevokedJobBeforeRenderingAndRemovesDelivery() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssistantJobs jobs = new AssistantJobs(jdbc, mock(AssistantAccounts.class),
            mock(AssistantDeletionJournal.class), mock(AssistantConversationReadAccess.class), 250);
        when(jdbc.queryForList(contains("available_at <= ?"), eq(String.class), anyLong()))
            .thenReturn(List.of(JOB));
        ResultSet record = mock(ResultSet.class);
        when(record.getString("id")).thenReturn(JOB);
        when(record.getString("workspace_id")).thenReturn(WORKSPACE);
        when(record.getString("account_id")).thenReturn(ACCOUNT);
        when(record.getString("conversation_id")).thenReturn(CONVERSATION);
        when(record.getString("status")).thenReturn("running");
        when(record.getInt("progress")).thenReturn(50);
        when(jdbc.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class), eq(JOB)))
            .thenAnswer(invocation -> {
                RowMapper<?> rowMapper = invocation.getArgument(1);
                return List.of(rowMapper.mapRow(record, 0));
            });
        when(jdbc.query(contains("SELECT available_at FROM assistant_job_outbox"),
            any(RowMapper.class), eq(JOB))).thenReturn(List.of(0L));

        jobs.advanceOne();

        verify(jdbc).queryForList(contains("assistant_workspace_member"), eq(CONVERSATION),
            eq(ACCOUNT), eq(WORKSPACE));
        verify(jdbc).update(contains("UPDATE assistant_job SET status"), eq("cancelled"), eq(50),
            isNull(), anyLong(), eq(JOB));
        verify(jdbc).update(contains("DELETE FROM assistant_job_outbox"), eq(JOB));
        verify(jdbc, never()).query(contains("WITH RECURSIVE ancestry"), any(RowMapper.class),
            any(Object[].class));
    }
}
