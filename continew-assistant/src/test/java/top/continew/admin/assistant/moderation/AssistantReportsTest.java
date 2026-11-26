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

package top.continew.admin.assistant.moderation;

import static org.assertj.core.api.Assertions.assertThat;
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

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantHostedSites;
import top.continew.admin.assistant.moderation.AssistantReports.Decision;
import top.continew.admin.assistant.moderation.AssistantReports.Operator;
import top.continew.admin.assistant.moderation.AssistantReports.ReportRequest;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.reminder.AssistantReminderTemplates;
import top.continew.admin.assistant.share.AssistantShares;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Tests report authorization, repeated commands and target withdrawal handling.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantReportsTest {

    private static final String WORKSPACE = "57f16978-1bc0-4afe-a1f6-eb95bf06e3f6";
    private static final String REPORT_ID = "f45065fc-5b7e-4fe4-a083-b0f1d0f7d039";
    private static final String TARGET_ID = "f0da5358-beea-492a-98be-63efeb2c2801";
    private static final String CONVERSATION_ID = "d15f94b3-17c0-4c77-9a51-c9b9f8c69012";
    private static final String ACCOUNT = "a synthetic account";
    private static final Operator OPERATOR = new Operator(42, 7);
    private static final Principal REPORTER = new Principal(
        new User(ACCOUNT, "synthetic", WORKSPACE, null), "synthetic-session");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AssistantShares shares = mock(AssistantShares.class);
    private final AssistantReports reports =
        new AssistantReports(jdbc, mock(AssistantDeletionJournal.class), shares,
            mock(AssistantHostedSites.class), mock(AssistantReminderTemplates.class));

    @Test
    void rejectsUnsupportedTargetTypesAndFreeFormReasonsBeforeReadingData() {
        expect("CAPABILITY_UNAVAILABLE",
            () -> reports.submit(REPORTER, new ReportRequest("unsupported", TARGET_ID, "safety")));
        expect("VALIDATION_ERROR",
            () -> reports.submit(REPORTER, new ReportRequest("shared_reply", TARGET_ID, "safety")));
        expect("VALIDATION_ERROR",
            () -> reports.submit(REPORTER,
                new ReportRequest("assistant_reply", TARGET_ID, "private notes")));
        verifyNoInteractions(jdbc);
    }

    @Test
    void deniesSharedReportIfTheReporterSessionWasRevokedBeforeStorage() {
        String token = "opaque fixture";
        String owner = "synthetic source account";
        when(shares.reportTargetForViewer(REPORTER, token, 1)).thenReturn(
            new ModerationTarget(owner, WORKSPACE, TARGET_ID, CONVERSATION_ID, 1, "synthetic"));
        expect("UNAUTHENTICATED", () -> reports.submit(REPORTER,
            new ReportRequest("shared_reply", null, "safety", token, 1, null)));
        verify(shares).reportTargetForViewer(REPORTER, token, 1);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsTargetThatIsOutsideTheConsumersVisibility() {
        currentReporter();
        expect("NOT_FOUND",
            () -> reports.submit(REPORTER,
                new ReportRequest("assistant_reply", TARGET_ID, "safety")));
        verify(jdbc).query(contains("c.deleted_at IS NULL"), any(RowMapper.class), eq(TARGET_ID),
            eq(ACCOUNT), eq(WORKSPACE));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void repeatsSameReportAndRejectsChangingTheReportReason() throws SQLException {
        currentReporter();
        ResultSet visible = mock(ResultSet.class);
        when(visible.getString("id")).thenReturn(CONVERSATION_ID);
        when(
            jdbc.query(contains("FROM assistant_chat_message"), any(RowMapper.class), eq(TARGET_ID),
                eq(ACCOUNT), eq(WORKSPACE)))
            .thenAnswer(invocation -> mappedRow(invocation, visible));

        ResultSet recorded = reportRow("open");
        when(jdbc.query(contains("WHERE workspace_id = ? AND reporter_account_id"),
            any(RowMapper.class),
            eq(WORKSPACE), eq(ACCOUNT), eq("assistant_reply"), eq(TARGET_ID)))
            .thenAnswer(invocation -> mappedRow(invocation, recorded));

        var repeat =
            reports.submit(REPORTER, new ReportRequest("assistant_reply", TARGET_ID, "safety"));
        assertThat(repeat.created()).isFalse();
        assertThat(repeat.receipt().id()).isEqualTo(REPORT_ID);
        expect("CONFLICT",
            () -> reports.submit(REPORTER,
                new ReportRequest("assistant_reply", TARGET_ID, "spam")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void requiresAnExplicitTenantWorkspaceGrantAndDecisionPrivilege() {
        expect("FORBIDDEN", () -> reports.detail(OPERATOR, WORKSPACE, REPORT_ID));
        when(jdbc.queryForList(contains("can_decide"), eq(Boolean.class), eq(7L), eq(42L),
            eq(WORKSPACE)))
            .thenReturn(List.of(false));
        expect("FORBIDDEN",
            () -> reports.decide(OPERATOR, WORKSPACE, REPORT_ID, new Decision("dismiss")));
        verify(jdbc, never()).query(contains("FROM assistant_content_report"), any(RowMapper.class),
            any(), any());
    }

    @Test
    void omitsPreviewAndAuditsUnavailableWhenTheOriginalTargetIsGone() throws SQLException {
        grantReport("open");
        var detail = reports.detail(OPERATOR, WORKSPACE, REPORT_ID);
        assertThat(detail.targetAvailable()).isFalse();
        assertThat(detail.preview()).isNull();
        assertThat(detail.evidenceDigest()).isEqualTo("a".repeat(64));
        var disposition = reports.decide(OPERATOR, WORKSPACE, REPORT_ID, new Decision("escalate"));
        assertThat(disposition.status()).isEqualTo("target_unavailable");
        verify(jdbc).update(contains("UPDATE assistant_content_report"), eq("target_unavailable"),
            eq(REPORT_ID), eq(WORKSPACE));
        verify(jdbc).update(contains("INSERT INTO assistant_report_audit"), eq(REPORT_ID), eq(42L),
            eq(7L), eq("escalate"), eq("target_unavailable"), any(Long.class));
    }

    @Test
    void repeatsSameOperatorDecisionWithoutAddingAnAuditRow() throws SQLException {
        grantReport("dismissed");
        ResultSet audit = mock(ResultSet.class);
        when(audit.getString("decision")).thenReturn("dismiss");
        when(jdbc.query(contains("FROM assistant_report_audit"), any(RowMapper.class),
            eq(REPORT_ID)))
            .thenAnswer(invocation -> mappedRow(invocation, audit));

        assertThat(reports.decide(OPERATOR, WORKSPACE, REPORT_ID, new Decision("dismiss")).status())
            .isEqualTo("dismissed");
        expect("CONFLICT",
            () -> reports.decide(OPERATOR, WORKSPACE, REPORT_ID, new Decision("escalate")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private void currentReporter() {
        when(jdbc.queryForList(contains("FROM assistant_session"), eq(String.class), eq(WORKSPACE),
            eq(REPORTER.sessionId()), eq(ACCOUNT), any(Long.class))).thenReturn(List.of("fixture"));
    }

    private void grantReport(String status) throws SQLException {
        when(jdbc.queryForList(contains("can_decide"), eq(Boolean.class), eq(7L), eq(42L),
            eq(WORKSPACE)))
            .thenReturn(List.of(true));
        ResultSet row = reportRow(status);
        when(jdbc.query(contains("WHERE workspace_id = ? AND id = ?"), any(RowMapper.class),
            eq(WORKSPACE), eq(REPORT_ID))).thenAnswer(invocation -> mappedRow(invocation, row));
    }

    private static ResultSet reportRow(String status) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("id")).thenReturn(REPORT_ID);
        when(result.getString("reporter_account_id")).thenReturn(ACCOUNT);
        when(result.getString("target_type")).thenReturn("assistant_reply");
        when(result.getString("target_id")).thenReturn(TARGET_ID);
        when(result.getString("conversation_id")).thenReturn(CONVERSATION_ID);
        when(result.getString("reason")).thenReturn("safety");
        when(result.getString("status")).thenReturn(status);
        when(result.getString("evidence_digest")).thenReturn("a".repeat(64));
        return result;
    }

    private static List<?> mappedRow(InvocationOnMock invocation, ResultSet result)
        throws SQLException {
        RowMapper<?> rowMapper = invocation.getArgument(1);
        return List.of(rowMapper.mapRow(result, 0));
    }

    private static void expect(String code, Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(AssistantApiException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
