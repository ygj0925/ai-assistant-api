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

package top.continew.admin.assistant.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantArtifacts.PublicationSource;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;

/**
 * Checks that public hosting and reporting refer to the same gated exact snapshot.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantHostedSiteReportTest {

    @Test
    void resolvesOnlyTheCurrentReleaseAndNeverPassesPrivateBodyIntoModeration() throws Exception {
        String siteId = "c70f5f90-a43d-4c50-b288-c7805516ec55";
        String revision = "209457e5-acef-452b-99f3-58d6499c5baa";
        String obsolete = "13cdd676-8cdc-4648-bb10-231c5dbdaaf3";
        String owner = "originating fixture owner";
        String sourceSpace = "originating fixture workspace";
        String reporterId = "independent fixture reporter";
        String reporterSpace = "fixture reporter workspace";
        String project = "private fixture project";
        String artifact = "private fixture artifact";
        String version = "private fixture artifact version";
        String membership = "active membership generation";
        String session = "active reporter session";
        byte[] html = "<p>Synthetic Webpage Body</p>".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(html));
        var reporter = new Principal(new User(reporterId, "synthetic", reporterSpace, null),
            session);
        var jdbc = mock(JdbcTemplate.class);
        var accounts = mock(AssistantAccounts.class);
        var artifacts = mock(AssistantArtifacts.class);
        var erasure = mock(AssistantErasure.class);
        var journal = mock(AssistantDeletionJournal.class);
        var sites = new AssistantHostedSites(jdbc, accounts, artifacts, erasure, journal, true,
            "http://public.localhost:18147");

        when(artifacts.webpageEditingAvailable()).thenReturn(true);
        when(jdbc.queryForList(contains("SELECT m.membership_revision"), eq(String.class),
            eq(reporterSpace), eq(reporterId), eq(session), any(Long.class)))
            .thenReturn(List.of(membership));
        when(accounts.allowsMembership(reporterId, reporterSpace, membership)).thenReturn(true);
        when(jdbc.queryForList(contains("w.personal_owner_id"), eq(String.class), eq(sourceSpace),
            eq(owner))).thenReturn(List.of(sourceSpace));
        var row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(siteId);
        when(row.getString("revision")).thenReturn(revision);
        when(row.getString("owner_id")).thenReturn(owner);
        when(row.getString("workspace_id")).thenReturn(sourceSpace);
        when(row.getString("project_id")).thenReturn(project);
        when(row.getString("artifact_id")).thenReturn(artifact);
        when(row.getString("version_id")).thenReturn(version);
        when(row.getString("content_digest")).thenReturn(digest);
        when(jdbc.query(contains("id = ?"), any(RowMapper.class), eq(siteId)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(row, 0));
            });
        var source = mock(PublicationSource.class);
        when(source.format()).thenReturn(AssistantWebpage.FORMAT);
        when(source.placementCurrent(journal, owner, sourceSpace)).thenReturn(true);
        when(source.renderedWebsite()).thenReturn(html);
        when(artifacts.publicationSource(owner, sourceSpace, project, artifact, version, true))
            .thenReturn(source);

        var displayed = sites.read("public.localhost", siteId);
        assertThat(displayed.html()).isEqualTo(html);
        assertThat(displayed.revision()).isEqualTo(revision);
        assertThat(sites.read("localhost", siteId)).isNull();
        var preview = sites.reportableVersion(reporter, siteId);
        assertThat(preview.publicUrl()).isEqualTo("http://public.localhost:18147/assistant-sites/"
            + siteId);
        assertThat(preview.revision()).isEqualTo(displayed.revision());

        assertThatThrownBy(() -> sites.reportTarget(reporter, siteId, obsolete))
            .isInstanceOfSatisfying(AssistantApiException.class,
                failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND"));
        var target = sites.reportTarget(reporter, siteId, preview.revision());
        assertThat(target.ownerId()).isEqualTo(owner);
        assertThat(target.workspaceId()).isEqualTo(sourceSpace);
        assertThat(target.targetId()).isEqualTo(revision);
        assertThat(target.content()).isEqualTo("hosted_site:" + digest);

        when(journal.blocksHostedSite(owner, sourceSpace, revision)).thenReturn(true);
        assertThat(sites.read("public.localhost", siteId)).isNull();
        assertThatThrownBy(() -> sites.reportTarget(reporter, siteId, revision))
            .isInstanceOfSatisfying(AssistantApiException.class,
                failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND"));
        verifyNoInteractions(erasure);
    }
}
