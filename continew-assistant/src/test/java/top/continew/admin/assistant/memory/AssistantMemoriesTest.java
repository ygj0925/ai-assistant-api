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

package top.continew.admin.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import top.continew.admin.assistant.memory.AssistantMemories.Settings;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.MemoryRevocations;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.project.AssistantProjects;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Checks the fail-closed storage boundary for manually supplied memory.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantMemoriesTest {

    private static final String OWNER = "e457c63b-77f9-48ea-a3c1-3cecd8b2b3e2";
    private static final String WORKSPACE = "42ec6438-a3aa-41a6-ba1a-c4509818003e";
    private static final String ENTRY = "92943e60-43be-416c-ad70-fd14535db8cd";

    private final User user = new User(OWNER, "synthetic", WORKSPACE, null);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
    private final AssistantMemories memories = new AssistantMemories(jdbc,
        mock(AssistantAccounts.class), mock(AssistantProjects.class), journal,
        mock(AssistantErasure.class), true);

    @BeforeEach
    void readyJournalAndActiveMembership() {
        when(journal.enabled()).thenReturn(true);
        when(journal.acceptsErasure()).thenReturn(true);
        when(journal.memoryRevocations(OWNER, WORKSPACE, null))
            .thenReturn(new MemoryRevocations(0, 0, 0));
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(OWNER), eq(WORKSPACE))).thenReturn(List.of(OWNER));
    }

    @Test
    void missingSettingsDoNotCreateRecordsOrReadEntryContent() {
        assertThat(memories.settings(user, null)).isEqualTo(new Settings(false, false, ""));
        assertThat(memories.list(user, null, null, null).items()).isEmpty();
        assertThatThrownBy(() -> memories.create(user, null, "This must not be persisted."))
            .isInstanceOfSatisfying(AssistantApiException.class,
                failure -> assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifiesNoEntryQuery();
    }

    @Test
    void pendingCutoffsMaskStaleControlsAndPreventReadingEntryTables() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBoolean("memory_enabled")).thenReturn(true);
        when(row.getBoolean("reference_history")).thenReturn(true);
        when(row.getString("instructions")).thenReturn("Retired instructions.");
        when(row.getLong("memory_epoch")).thenReturn(80L);
        when(row.getLong("history_epoch")).thenReturn(100L);
        when(row.getLong("instructions_epoch")).thenReturn(120L);
        when(jdbc.query(contains("FROM assistant_memory_settings"),
            ArgumentMatchers.<RowMapper<Object>>any(), eq(OWNER), eq(WORKSPACE), eq("personal"),
            eq(WORKSPACE))).thenAnswer(
                call -> List.of(call.<RowMapper<Object>>getArgument(1)
                    .mapRow(row, 0)));
        when(journal.memoryRevocations(OWNER, WORKSPACE, null))
            .thenReturn(new MemoryRevocations(80L, 100L, 120L));

        assertThat(memories.settings(user, null)).isEqualTo(new Settings(false, false, ""));
        assertThat(memories.list(user, null, null, null).items()).isEmpty();
        assertThatThrownBy(() -> memories.get(user, null, ENTRY))
            .isInstanceOfSatisfying(AssistantApiException.class,
                failure -> assertThat(failure.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verifiesNoEntryQuery();
    }

    private void verifiesNoEntryQuery() {
        verify(jdbc, never()).query(contains("FROM assistant_memory_entry"),
            ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        verify(jdbc, never()).queryForObject(contains("FROM assistant_memory_entry"),
            eq(Integer.class), any(Object[].class));
    }
}
