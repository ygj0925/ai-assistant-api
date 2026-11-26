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

package top.continew.admin.assistant.customassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjectResources;
import top.continew.admin.assistant.project.AssistantProjects;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Checks durable scoped identities and reuse of current material authorization.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantCustomAssistantsTest {

    private static final String OWNER_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String PROJECT_ID = UUID.randomUUID().toString();
    private static final String VERSION_ID = UUID.randomUUID().toString();
    private static final String ASSISTANT_ID = UUID.randomUUID().toString();
    private static final String MATERIAL_ID = UUID.randomUUID().toString();
    private static final User OWNER = new User(OWNER_ID, null, WORKSPACE_ID, null);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
    private final AssistantProjectResources resources = mock(AssistantProjectResources.class);
    private final AssistantCustomAssistants service = new AssistantCustomAssistants(jdbc,
        mock(AssistantAccounts.class), mock(AssistantProjects.class), resources, journal,
        mock(AssistantErasure.class), true);

    @Test
    void derivesStableCreationIdentityOnlyWithinOneAccountAndWorkspace() {
        String key = UUID.randomUUID().toString();
        String expected = AssistantCustomAssistants.createId(OWNER, key);
        assertThat(UUID.fromString(expected).version()).isEqualTo(8);
        assertThat(AssistantCustomAssistants.createId(OWNER, key)).isEqualTo(expected);
        assertThat(AssistantCustomAssistants.createId(new User(OWNER_ID, "irrelevant", WORKSPACE_ID,
            null), key)).isEqualTo(expected);
        assertThat(AssistantCustomAssistants.createId(new User(OWNER_ID, null, PROJECT_ID, null),
            key)).isNotEqualTo(expected);
        assertThat(
            AssistantCustomAssistants.createId(new User(PROJECT_ID, null, WORKSPACE_ID, null),
                key))
            .isNotEqualTo(expected);
    }

    @Test
    void rejectsWrongScopeAndStaleHeadBeforeResolvingPrivateText() throws SQLException {
        fixture();
        assertStatus(HttpStatus.NOT_FOUND,
            () -> service.bindForRun(OWNER, ASSISTANT_ID, WORKSPACE_ID));
        assertStatus(HttpStatus.CONFLICT,
            () -> service.resolveForRun(OWNER, ASSISTANT_ID, MATERIAL_ID, PROJECT_ID));
        when(journal.customAssistantCutoff(OWNER_ID, WORKSPACE_ID, ASSISTANT_ID)).thenReturn(10L);
        assertStatus(HttpStatus.CONFLICT,
            () -> service.resolveForRun(OWNER, ASSISTANT_ID, VERSION_ID, PROJECT_ID));
        verifyNoInteractions(resources);
    }

    @Test
    void rechecksCurrentSourcePermissionWhenUsingAPreviouslyValidBinding() throws SQLException {
        fixture();
        when(jdbc.queryForList(contains("FROM assistant_custom_assistant_source"), eq(String.class),
            eq(ASSISTANT_ID), eq(VERSION_ID))).thenReturn(List.of(MATERIAL_ID));
        var binding = service.bindForRun(OWNER, ASSISTANT_ID, PROJECT_ID);
        assertThat(binding.versionId()).isEqualTo(VERSION_ID);
        verify(resources).get(OWNER, PROJECT_ID, MATERIAL_ID);
        when(resources.get(OWNER, PROJECT_ID, MATERIAL_ID)).thenThrow(
            new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Removed."));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.resolveForRun(OWNER,
            binding.assistantId(), binding.versionId(), PROJECT_ID));
    }

    private void fixture() throws SQLException {
        when(journal.enabled()).thenReturn(true);
        when(journal.acceptsErasure()).thenReturn(true);
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(OWNER_ID), eq(WORKSPACE_ID))).thenReturn(List.of(OWNER_ID));
        ResultSet assistant = mock(ResultSet.class);
        when(assistant.getString("id")).thenReturn(ASSISTANT_ID);
        when(assistant.getString("project_id")).thenReturn(PROJECT_ID);
        when(assistant.getString("head_version_id")).thenReturn(VERSION_ID);
        when(assistant.getLong("head_epoch")).thenReturn(10L);
        when(jdbc.query(contains("JOIN assistant_custom_assistant_version"), any(RowMapper.class),
            eq(OWNER_ID), eq(WORKSPACE_ID), eq(ASSISTANT_ID))).thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(assistant, 0));
            });

        ResultSet version = mock(ResultSet.class);
        when(version.getString("id")).thenReturn(VERSION_ID);
        when(version.getString("assistant_id")).thenReturn(ASSISTANT_ID);
        when(version.getString("name")).thenReturn("Manual assistant");
        when(version.getString("instructions")).thenReturn("");
        when(jdbc.query(contains("FROM assistant_custom_assistant_version WHERE"),
            any(RowMapper.class),
            eq(ASSISTANT_ID), eq(VERSION_ID))).thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(version, 0));
            });
    }

    private static void assertStatus(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AssistantApiException.class,
            failure -> assertThat(failure.status()).isEqualTo(status));
    }
}
