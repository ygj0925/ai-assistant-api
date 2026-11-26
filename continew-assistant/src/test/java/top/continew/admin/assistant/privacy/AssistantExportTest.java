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

package top.continew.admin.assistant.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.MemoryRevocations;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Tests portable export structure, scope and bounded pagination.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantExportTest {

    private static final String ACCOUNT = "f45065fc-5b7e-4fe4-a083-b0f1d0f7d039";
    private static final String WORKSPACE = "57f16978-1bc0-4afe-a1f6-eb95bf06e3f6";
    private static final String SESSION = "synthetic-session-id";
    private static final Principal OWNER =
        new Principal(new User(ACCOUNT, "synthetic", WORKSPACE, null), SESSION);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
    private final AssistantExport export = new AssistantExport(jdbc, mapper, journal);

    @Test
    void includesOnlyPortableProfileFieldsAndAnExplicitFinalCursor() throws Exception {
        liveProfile();
        byte[] bytes = export.download(OWNER, null);
        var body = mapper.readTree(bytes);
        assertThat(body.path("schemaVersion").asInt()).isOne();
        assertThat(body.path("account").path("id").asText()).isEqualTo(ACCOUNT);
        assertThat(body.path("account").path("preferences").path("locale").asText())
            .isEqualTo("zh-CN");
        assertThat(body.path("nextCursor").isNull()).isTrue();
        assertThat(body.path("conversations").isEmpty()).isTrue();
        assertThat(body.path("messages").isEmpty()).isTrue();
        assertThat(body.path("runs").isEmpty()).isTrue();
        assertThat(body.path("projectResources").isEmpty()).isTrue();
        assertThat(body.path("analyses").isEmpty()).isTrue();
        assertThat(body.path("memorySettings").isArray()).isTrue();
        assertThat(body.path("memoryEntries").isArray()).isTrue();
        assertThat(body.path("attachments").isArray()).isTrue();
        assertThat(body.path("customAssistants").isArray()).isTrue();
        assertThat(body.path("customAssistantVersions").isArray()).isTrue();
        assertThat(body.path("reminders").isArray()).isTrue();
        assertThat(body.path("reminderOccurrences").isArray()).isTrue();
        assertThat(body.path("privateArtifacts").isArray()).isTrue();
        assertThat(body.path("privateArtifactVersions").isArray()).isTrue();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain(SESSION, "password",
            "token");
    }

    @Test
    void bindsContinuationToTheSameScopeAndResumesAtTheLastRecord() throws Exception {
        liveProfile();
        ResultSet result = mock(ResultSet.class);
        when(result.getString("id")).thenReturn(id(1));
        when(result.getString("title")).thenReturn("合成标题");
        when(jdbc.query(contains("FROM assistant_conversation c"), any(RowMapper.class),
            eq(ACCOUNT), eq(WORKSPACE), eq(""), eq(AssistantExport.PAGE_SIZE + 1)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                var records = new ArrayList<>(
                    Collections.nCopies(AssistantExport.PAGE_SIZE, mapper.mapRow(result, 0)));
                records.add(mapper.mapRow(result, AssistantExport.PAGE_SIZE));
                return records;
            });
        var first = mapper.readTree(export.download(OWNER, null));
        assertThat(first.path("conversations").size()).isEqualTo(AssistantExport.PAGE_SIZE);
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        ResultSet next = mock(ResultSet.class);
        when(next.getString("id")).thenReturn(id(2));
        when(jdbc.query(contains("FROM assistant_conversation c"), any(RowMapper.class),
            eq(ACCOUNT), eq(WORKSPACE), eq(id(1)), eq(AssistantExport.PAGE_SIZE + 1)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(next, 0));
            });
        var second = mapper.readTree(export.download(OWNER, cursor));
        assertThat(second.path("conversations").get(0).path("id").asText()).isEqualTo(id(2));
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void rejectsMalformedAndCrossAccountCursorsBeforeReadingData() {
        var foreign = "1:" + id(1) + ":" + WORKSPACE + ":MESSAGES:" + id(2);
        String cursor = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(foreign.getBytes(StandardCharsets.US_ASCII));
        expectStatus(400, () -> export.download(OWNER, cursor));
        expectStatus(400, () -> export.download(OWNER, "*"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsDeletedOrRevokedSessionsInsideTheExportRead() {
        expectStatus(401, () -> export.download(OWNER, null));
    }

    @Test
    void refusesAnExportWhenConversationPolicyChangedDuringAssembly() throws SQLException {
        liveProfile();
        when(journal.conversationReadFence(ACCOUNT, WORKSPACE))
            .thenReturn(null, "changed-read-policy");
        expectStatus(409, () -> export.download(OWNER, null));
    }

    @Test
    void refusesAnOversizedLegacyContentRecordWithoutReturningAPartialFile() throws SQLException {
        liveProfile();
        ResultSet oversized = mock(ResultSet.class);
        when(jdbc.query(contains("FROM assistant_chat_message m"), any(RowMapper.class),
            eq(ACCOUNT), eq(WORKSPACE), eq(""), eq(AssistantExport.PAGE_SIZE + 1)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(oversized, 0));
            });
        expectStatus(413, () -> export.download(OWNER, null));
    }

    @Test
    void continuesAnOldCursorAndSerializesTheResourceAndItsFullDerivedReport() throws Exception {
        liveProfile();
        ResultSet resource = mock(ResultSet.class);
        when(resource.getString("id")).thenReturn(id(1));
        when(resource.getString("project_id")).thenReturn(id(2));
        when(resource.getString("name")).thenReturn("合成资料");
        when(resource.getString("content")).thenReturn("Value\r\n42\r\n");
        when(jdbc.query(contains("FROM assistant_project_resource r"), any(RowMapper.class),
            eq(ACCOUNT), eq(WORKSPACE), eq(""), eq(AssistantExport.PAGE_SIZE + 1)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(resource, 0));
            });
        syntheticAnalysis();
        var document = mapper.readTree(export.download(OWNER, cursor("RUNS", "")));
        assertThat(document.path("projectResources").get(0).path("id").asText()).isEqualTo(id(1));
        assertThat(document.path("projectResources").get(0).path("content").asText())
            .isEqualTo("Value\r\n42\r\n");
        var report = document.path("analyses").get(0);
        assertThat(report.path("id").asText()).isEqualTo(id(3));
        assertThat(report.path("resourceId").asText()).isEqualTo(id(1));
        assertThat(report.path("artifactCsv").asText()).isEqualTo("row,value\r\n1,42\r\n");
        assertThat(report.path("series").get(0).path("value").asText()).isEqualTo("42");
        assertThat(report.path("logSummary").path("blankValuesSkipped").asInt()).isZero();
        assertThat(document.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void withholdsTheEntireSnapshotIfTheDerivedReportSourceWasRevoked() throws Exception {
        liveProfile();
        syntheticAnalysis();
        when(journal.allowsExportSnapshot(ACCOUNT, WORKSPACE, Set.of(), Set.of(id(1)),
            List.of(), List.of(), List.of()))
            .thenReturn(false);
        expectStatus(503, () -> export.download(OWNER, cursor("ANALYSES", "")));
    }

    @Test
    void exportsRetainedDisabledMemoryWhileRedactingStaleInstructionsAndStorageDetails()
        throws Exception {
        liveProfile();
        when(journal.memoryRevocations(ACCOUNT, WORKSPACE, null))
            .thenReturn(new MemoryRevocations(100, 100, 100));
        when(journal.memoryRevocations(ACCOUNT, WORKSPACE, id(2)))
            .thenReturn(new MemoryRevocations(0, 0, 0));

        ResultSet personal = mock(ResultSet.class);
        when(personal.getString("scope_id")).thenReturn(WORKSPACE);
        when(personal.getString("scope_kind")).thenReturn("personal");
        when(personal.getString("instructions")).thenReturn("Synthetic stale instruction");
        when(personal.getBoolean("memory_enabled")).thenReturn(true);
        when(personal.getBoolean("reference_history")).thenReturn(true);
        when(personal.getLong("memory_epoch")).thenReturn(100L);
        when(personal.getLong("history_epoch")).thenReturn(102L);
        when(personal.getLong("instructions_epoch")).thenReturn(99L);
        fixtureRow("AND s.scope_kind = 'personal'", personal);

        ResultSet project = mock(ResultSet.class);
        when(project.getString("scope_id")).thenReturn(id(2));
        when(project.getString("scope_kind")).thenReturn("project");
        when(project.getString("project_id")).thenReturn(id(2));
        when(project.getString("instructions")).thenReturn("");
        when(project.getBoolean("memory_enabled")).thenReturn(true);
        when(project.getLong("memory_epoch")).thenReturn(104L);
        fixtureRow("AND s.scope_kind = 'project'", project);

        ResultSet memory = mock(ResultSet.class);
        when(memory.getString("id")).thenReturn(id(3));
        when(memory.getString("scope_kind")).thenReturn("personal");
        when(memory.getString("content")).thenReturn("Retained manual data");
        fixtureRow("FROM assistant_memory_entry e", memory);
        ResultSet attachment = mock(ResultSet.class);
        when(attachment.getString("id")).thenReturn(id(4));
        when(attachment.getString("project_id")).thenReturn(id(2));
        when(attachment.getString("media_kind")).thenReturn("PNG");
        when(attachment.getInt("size_bytes")).thenReturn(70);
        when(attachment.getInt("width")).thenReturn(1);
        when(attachment.getInt("height")).thenReturn(1);
        fixtureRow("FROM assistant_attachment a", attachment);

        byte[] result = export.download(OWNER, cursor("ANALYSES", ""));
        var body = mapper.readTree(result);
        assertThat(body.path("schemaVersion").asInt()).isOne();
        var settings = body.path("memorySettings");
        assertThat(settings.size()).isEqualTo(2);
        assertThat(settings.get(0).path("enabled").asBoolean()).isFalse();
        assertThat(settings.get(0).path("referenceHistory").asBoolean()).isTrue();
        assertThat(settings.get(0).path("instructions").asText()).isEmpty();
        assertThat(settings.get(0).has("projectId")).isFalse();
        assertThat(settings.get(1).path("enabled").asBoolean()).isTrue();
        assertThat(settings.get(1).path("projectId").asText()).isEqualTo(id(2));
        assertThat(settings.get(1).has("instructions")).isFalse();
        assertThat(settings.get(1).has("referenceHistory")).isFalse();
        assertThat(body.path("memoryEntries").get(0).path("content").asText())
            .isEqualTo("Retained manual data");
        var image = body.path("attachments").get(0);
        assertThat(image.path("contentType").asText()).isEqualTo("image/png");
        assertThat(image.path("size").asInt()).isEqualTo(70);
        assertThat(new String(result, StandardCharsets.UTF_8))
            .doesNotContain("Synthetic stale instruction", "Epoch", "digest", "token", "objectKey");
    }

    @Test
    void exportsOnlyMetadataForApprovedPdfText() throws Exception {
        liveProfile();
        ResultSet pdf = mock(ResultSet.class);
        when(pdf.getString("id")).thenReturn(id(4));
        when(pdf.getString("project_id")).thenReturn(id(2));
        when(pdf.getString("media_kind")).thenReturn("PDF");
        when(pdf.getInt("size_bytes")).thenReturn(42);
        fixtureRow("FROM assistant_attachment a", pdf);

        var document = mapper.readTree(export.download(OWNER, cursor("ATTACHMENTS", "")));
        var attachment = document.path("attachments").get(0);
        assertThat(attachment.path("contentType").asText()).isEqualTo("text/plain");
        assertThat(attachment.path("size").asInt()).isEqualTo(42);
        assertThat(attachment.hasNonNull("width")).isFalse();
        assertThat(attachment.hasNonNull("height")).isFalse();
        assertThat(attachment.hasNonNull("audio")).isFalse();
        assertThat(attachment.has("text")).isFalse();
        assertThat(attachment.has("body")).isFalse();
    }

    @Test
    void exportsTheCurrentOwnerProjectionAndAuthoredHistoryAfterAnOldCursor() throws Exception {
        liveProfile();
        customHead();
        reminder();
        when(journal.customAssistantCutoff(ACCOUNT, WORKSPACE, id(1))).thenReturn(110L);
        when(journal.reminderPauseCutoff(ACCOUNT, WORKSPACE, id(4))).thenReturn(120L);

        ResultSet version = mock(ResultSet.class);
        when(version.getString("id")).thenReturn(id(2));
        when(version.getString("assistant_id")).thenReturn(id(1));
        when(version.getString("instructions")).thenReturn("合成旧版助手指令");
        when(version.getString("tool_id")).thenReturn("project_resource_lookup");
        fixtureRow("FROM assistant_custom_assistant_version v", version);
        ResultSet source = mock(ResultSet.class);
        when(source.getString("version_id")).thenReturn(id(2));
        when(source.getString("resource_id")).thenReturn(id(5));
        when(jdbc.query(contains("FROM assistant_custom_assistant_source s"), any(RowMapper.class),
            eq(ACCOUNT), eq(WORKSPACE), eq(id(2)))).thenAnswer(invocation -> {
                RowMapper<?> row = invocation.getArgument(1);
                return List.of(row.mapRow(source, 0));
            });
        ResultSet occurrence = mock(ResultSet.class);
        when(occurrence.getString("id")).thenReturn(id(6));
        when(occurrence.getString("reminder_id")).thenReturn(id(4));
        when(occurrence.getString("outcome")).thenReturn("delivered");
        when(occurrence.wasNull()).thenReturn(true);
        fixtureRow("FROM assistant_reminder_occurrence o", occurrence);

        byte[] result = export.download(OWNER, cursor("ATTACHMENTS", ""));
        var body = mapper.readTree(result);
        assertThat(body.path("customAssistants").get(0).path("status").asText())
            .isEqualTo("requires_review");
        var authored = body.path("customAssistantVersions").get(0);
        assertThat(authored.path("instructions").asText()).isEqualTo("合成旧版助手指令");
        assertThat(authored.path("resourceIds").get(0).asText()).isEqualTo(id(5));
        assertThat(authored.path("tools").get(0).asText()).isEqualTo("project_resource_lookup");
        var schedule = body.path("reminders").get(0);
        assertThat(schedule.path("state").asText()).isEqualTo("paused");
        assertThat(schedule.path("quietHours").path("end").asText()).isEqualTo("07:00");
        var notification = body.path("reminderOccurrences").get(0);
        assertThat(notification.path("reminderId").asText()).isEqualTo(id(4));
        assertThat(notification.has("readAt")).isFalse();
        assertThat(new String(result, StandardCharsets.UTF_8)).doesNotContain("nextDeliveryAt",
            "nextScheduledAt",
            "epoch", "cutoff", "digest", "reminderLinks");
    }

    @Test
    void rejectsTheWholePageIfTheReviewedHeadOrPauseWatermarkChangesDuringEncoding()
        throws Exception {
        liveProfile();
        customHead();
        when(journal.customAssistantCutoff(ACCOUNT, WORKSPACE, id(1))).thenReturn(0L, 120L);
        expectStatus(503, () -> export.download(OWNER, cursor("CUSTOM_ASSISTANTS", "")));

        reminder();
        when(journal.reminderPauseCutoff(ACCOUNT, WORKSPACE, id(4))).thenReturn(0L, 120L);
        expectStatus(503, () -> export.download(OWNER, cursor("REMINDERS", "")));
    }

    @Test
    void retainsExactPrivateArtifactTextAndAncestryAfterTheFormerFinalSection() throws Exception {
        liveProfile();
        ResultSet summary = mock(ResultSet.class);
        when(summary.getString("id")).thenReturn(id(1));
        when(summary.getString("head_version_id")).thenReturn(id(2));
        when(summary.getString("project_id")).thenReturn(id(4));
        when(summary.getString("source_conversation_id")).thenReturn(id(5));
        when(summary.getString("source_message_id")).thenReturn(id(6));
        when(summary.getString("format")).thenReturn("markdown");
        fixtureRow("FROM assistant_artifact a", summary);
        String content = "# 合成文稿 🦀\r\n\texact spacing \n";
        fixtureArtifactVersion(content, "markdown");

        var body = mapper.readTree(export.download(OWNER, cursor("REMINDER_HISTORY", "")));
        var artifact = body.path("privateArtifacts").get(0);
        assertThat(artifact.path("id").asText()).isEqualTo(id(1));
        assertThat(artifact.path("headVersionId").asText()).isEqualTo(id(2));
        assertThat(artifact.path("sourceMessageId").asText()).isEqualTo(id(6));
        assertThat(artifact.has("sourceConversationId")).isFalse();
        var version = body.path("privateArtifactVersions").get(0);
        assertThat(version.path("content").asText().getBytes(StandardCharsets.UTF_8))
            .isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        assertThat(version.path("restoredFromVersionId").asText()).isEqualTo(id(3));
        assertThat(version.has("sourceConversationId")).isFalse();
        assertThat(version.has("projectId")).isFalse();
    }

    @Test
    void declinesAContinuedPageWhenAnArtifactVersionOrItsSourceIsRevoked() throws Exception {
        liveProfile();
        fixtureArtifactVersion("Synthetic current draft", "text");
        when(journal.blocksArtifact(ACCOUNT, WORKSPACE, id(1), id(5))).thenReturn(true);
        expectStatus(503, () -> export.download(OWNER, cursor("ARTIFACT_VERSIONS", "")));
    }

    @Test
    void deniesTheSnapshotWhenTheReferencedApprovedImageIsRevokedDuringExport() throws Exception {
        liveProfile();
        ResultSet stored = fixtureArtifactVersion(
            mapper.writeValueAsString(mapper.createObjectNode()
                .put("attachmentId", id(7)).put("altText", "验收图片")),
            "image");
        when(stored.getString("source_conversation_id")).thenReturn(null);
        when(stored.getString("image_attachment_id")).thenReturn(id(7));
        when(stored.getInt("image_size")).thenReturn(70);
        when(stored.getInt("image_height")).thenReturn(1);
        when(stored.getInt("image_width")).thenReturn(1);
        String after = cursor("ARTIFACT_VERSIONS", "");
        var body = mapper.readTree(export.download(OWNER, after));
        var version = body.path("privateArtifactVersions").get(0);
        assertThat(version.path("image").path("attachmentId").asText()).isEqualTo(id(7));
        assertThat(version.path("image").path("altText").asText()).isEqualTo("验收图片");
        assertThat(version.has("content")).isFalse();
        when(journal.allowsExportSnapshot(ACCOUNT, WORKSPACE, Set.of(), Set.of(),
            List.of(), List.of(id(7)), List.of())).thenReturn(false);
        expectStatus(503, () -> export.download(OWNER, after));
    }

    @Test
    void exportsTypedSpreadsheetValuesWithoutThePrivateEncoding() throws Exception {
        liveProfile();
        fixtureArtifactVersion("""
            {"name":"合成工作表","rows":[["=1+2",12.25,null,true]]}
            """, "sheet");
        var body = mapper.readTree(export.download(OWNER, cursor("ARTIFACT_VERSIONS", "")));
        var sheetVersion = body.path("privateArtifactVersions").get(0);
        assertThat(sheetVersion.has("content")).isFalse();
        var sheet = sheetVersion.path("sheet");
        assertThat(sheet.path("name").asText()).isEqualTo("合成工作表");
        assertThat(sheet.path("rows").get(0).get(0).asText()).isEqualTo("=1+2");
        assertThat(sheet.path("rows").get(0).get(1).asDouble()).isEqualTo(12.25);
        assertThat(sheet.path("rows").get(0).get(2).isNull()).isTrue();
        assertThat(sheet.path("rows").get(0).get(3).asBoolean()).isTrue();
    }

    @Test
    void exportsAuthoredSlidesWithoutThePrivateEncoding() throws Exception {
        liveProfile();
        fixtureArtifactVersion("""
            {"slides":[{"title":"合成演示","bullets":["第一条","第二条"]}]}
            """, "slides");
        var body = mapper.readTree(export.download(OWNER, cursor("ARTIFACT_VERSIONS", "")));
        var version = body.path("privateArtifactVersions").get(0);
        assertThat(version.has("content")).isFalse();
        assertThat(version.has("sheet")).isFalse();
        var slide = version.path("presentation").path("slides").get(0);
        assertThat(slide.path("title").asText()).isEqualTo("合成演示");
        assertThat(slide.path("bullets").get(1).asText()).isEqualTo("第二条");
    }

    @Test
    void exportsLiteralCodeSourceWithItsOriginalLineEndingsAndLanguage() throws Exception {
        liveProfile();
        String source = "# 合成源稿 🦀\r\nprint('literal')\r\n ";
        String json = mapper.writeValueAsString(
            mapper.createObjectNode().put("language", "python").put("source", source));
        fixtureArtifactVersion(json, "code");
        var body = mapper.readTree(export.download(OWNER, cursor("ARTIFACT_VERSIONS", "")));
        var version = body.path("privateArtifactVersions").get(0);
        assertThat(version.has("content")).isFalse();
        assertThat(version.has("sheet")).isFalse();
        assertThat(version.has("presentation")).isFalse();
        assertThat(version.path("code").path("language").asText()).isEqualTo("python");
        assertThat(version.path("code").path("source").asText()).isEqualTo(source);
    }

    @Test
    void exportsStructuredWebpageWithoutItsGeneratedMarkup() throws Exception {
        liveProfile();
        fixtureArtifactVersion(
            """
                {"heading":"私有页面","introduction":"保留文字","sections":[{"title":"区块","paragraphs":["<p>文字</p>"]}]}
                """,
            "webpage");
        var body = mapper.readTree(export.download(OWNER, cursor("ARTIFACT_VERSIONS", "")));
        var version = body.path("privateArtifactVersions").get(0);
        assertThat(version.has("content")).isFalse();
        assertThat(version.path("website").path("heading").asText()).isEqualTo("私有页面");
        assertThat(
            version.path("website").path("sections").get(0).path("paragraphs").get(0).asText())
            .isEqualTo("<p>文字</p>");
        assertThat(body.toString()).doesNotContain("Content-Security-Policy");
    }

    private ResultSet fixtureArtifactVersion(String content, String format) throws SQLException {
        ResultSet version = mock(ResultSet.class);
        when(version.getString("id")).thenReturn(id(2));
        when(version.getString("artifact_id")).thenReturn(id(1));
        when(version.getString("restored_from_version_id")).thenReturn(id(3));
        when(version.getString("source_conversation_id")).thenReturn(id(5));
        when(version.getString("content")).thenReturn(content);
        when(version.getString("format")).thenReturn(format);
        fixtureRow("FROM assistant_artifact_version v", version);
        return version;
    }

    private void customHead() throws SQLException {
        ResultSet head = mock(ResultSet.class);
        when(head.getString("id")).thenReturn(id(1));
        when(head.getString("head_version_id")).thenReturn(id(2));
        when(head.getLong("head_epoch")).thenReturn(110L);
        fixtureRow("FROM assistant_custom_assistant a", head);
    }

    private void reminder() throws SQLException {
        ResultSet schedule = mock(ResultSet.class);
        when(schedule.getString("id")).thenReturn(id(4));
        when(schedule.getString("status")).thenReturn("active");
        when(schedule.getString("quiet_start")).thenReturn("23:00");
        when(schedule.getString("quiet_end")).thenReturn("07:00");
        when(schedule.getLong("config_epoch")).thenReturn(110L);
        fixtureRow("FROM assistant_reminder r", schedule);
    }

    private void fixtureRow(String matching, ResultSet fixture) throws SQLException {
        when(jdbc.query(contains(matching), any(RowMapper.class), eq(ACCOUNT), eq(WORKSPACE),
            eq(""), any(Integer.class))).thenAnswer(invocation -> {
                RowMapper<?> row = invocation.getArgument(1);
                return List.of(row.mapRow(fixture, 0));
            });
    }

    private void syntheticAnalysis() throws SQLException {
        ResultSet analysis = mock(ResultSet.class);
        when(analysis.getString("id")).thenReturn(id(3));
        when(analysis.getString("project_id")).thenReturn(id(2));
        when(analysis.getString("resource_id")).thenReturn(id(1));
        when(analysis.getString("value_column")).thenReturn("Value");
        when(analysis.getString("series_json")).thenReturn("[{\"row\":1,\"value\":\"42\"}]");
        when(analysis.getString("artifact_csv")).thenReturn("row,value\r\n1,42\r\n");
        when(analysis.getString("algorithm_version")).thenReturn("csv_numeric_v1");
        when(analysis.getInt("row_count")).thenReturn(1);
        when(analysis.getInt("value_count")).thenReturn(1);
        when(jdbc.query(contains("FROM assistant_data_analysis a"), any(RowMapper.class),
            eq(ACCOUNT), eq(WORKSPACE), eq(""), any(Integer.class))).thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(analysis, 0));
            });
    }

    private void liveProfile() throws SQLException {
        when(journal.allowsExportSnapshot(eq(ACCOUNT), eq(WORKSPACE), any(), any(), any(), any(),
            any()))
            .thenReturn(true);
        ResultSet profile = mock(ResultSet.class);
        when(profile.getString("id")).thenReturn(ACCOUNT);
        when(profile.getString("username")).thenReturn("synthetic");
        when(profile.getString("locale")).thenReturn("zh-CN");
        when(profile.getString("theme")).thenReturn("system");
        when(jdbc.query(contains("JOIN assistant_session"), any(RowMapper.class),
            eq(ACCOUNT), eq(WORKSPACE), eq(SESSION), any(Long.class))).thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(profile, 0));
            });
    }

    private static String id(int value) {
        return "00000000-0000-0000-0000-%012d".formatted(value);
    }

    private static String cursor(String section, String after) {
        String value = "1:" + ACCOUNT + ":" + WORKSPACE + ":" + section + ":" + after;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void expectStatus(int status, Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(AssistantApiException.class,
                exception -> assertThat(exception.status().value()).isEqualTo(status));
    }
}
