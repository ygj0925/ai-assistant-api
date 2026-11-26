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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Kind;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Material;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Proof;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Scope;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.MemoryRevocations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Protects lease scope, opt-in settings and source budget while selecting model inputs.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantGenerationMemoryTest {

    private static final String ACCOUNT = "account";
    private static final String WORKSPACE = "workspace";
    private static final String CONVERSATION = "conversation";
    private static final String PROJECT = "project";
    private static final String RUN = "run";
    private static final String LEASE = "lease";
    private static final String ENTRY = "entry";
    private static final Scope PERSONAL = new Scope(ACCOUNT, WORKSPACE, CONVERSATION, null);
    private static final Scope FILED = new Scope(ACCOUNT, WORKSPACE, CONVERSATION, PROJECT);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
    private final AssistantProjectMemorySources sources = mock(AssistantProjectMemorySources.class);
    private final AssistantGenerationMemory adapter = new AssistantGenerationMemory(jdbc, journal,
        sources, true, true);

    @BeforeEach
    void prepareJournalAndOwnedScope() {
        when(journal.acceptsErasure()).thenReturn(true);
        when(journal.memoryRevocations(eq(ACCOUNT), eq(WORKSPACE),
            ArgumentMatchers.nullable(String.class)))
            .thenReturn(new MemoryRevocations(0, 0, 0));
        when(jdbc.queryForList(contains("FROM assistant_account"), eq(String.class), eq(ACCOUNT)))
            .thenReturn(List.of(ACCOUNT));
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(ACCOUNT), eq(WORKSPACE))).thenReturn(List.of(ACCOUNT));
        when(jdbc.queryForList(contains("FROM assistant_conversation"), eq(String.class),
            eq(CONVERSATION), eq(ACCOUNT), eq(WORKSPACE))).thenReturn(List.of(CONVERSATION));
        when(jdbc.queryForList(contains("FROM assistant_project"), eq(String.class), eq(PROJECT),
            eq(ACCOUNT), eq(WORKSPACE))).thenReturn(List.of(PROJECT));
    }

    @Test
    void disabledPolicyMissingJournalOrNoRoomLeavesStorageUnread() {
        assertThat(new AssistantGenerationMemory(jdbc, journal, sources, true, false)
            .select(RUN, LEASE, 200, 3)).isEmpty();
        assertThat(adapter.select(RUN, LEASE, 0, 3)).isEmpty();
        assertThat(adapter.select(RUN, LEASE, 200, 0)).isEmpty();
        when(journal.acceptsErasure()).thenReturn(false);
        assertThat(adapter.select(RUN, LEASE, 200, 3)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void selectedPersonalSourcesFitExactlyWithoutUsingStrayOrRetiredText() throws SQLException {
        scoped(PERSONAL);
        controls("personal", WORKSPACE, true, 10, 20);
        when(jdbc.queryForList(startsWith("SELECT instructions FROM assistant_memory_settings"),
            eq(String.class), eq(ACCOUNT), eq(WORKSPACE), eq("personal"), eq(WORKSPACE), eq(20L),
            eq(4))).thenReturn(List.of("🦀a"));
        when(jdbc.queryForList(startsWith("SELECT id FROM assistant_memory_entry"),
            eq(String.class), eq(ACCOUNT), eq(WORKSPACE), eq("personal"), eq(WORKSPACE), eq(2)))
            .thenReturn(List.of(ENTRY));
        when(jdbc.queryForList(startsWith("SELECT content FROM assistant_memory_entry"),
            eq(String.class), eq(ACCOUNT), eq(WORKSPACE), eq("personal"), eq(WORKSPACE), eq(ENTRY)))
            .thenReturn(List.of("乙丙"));
        var selected = adapter.select(RUN, LEASE, 4, 2).orElseThrow();
        assertThat(selected.material()).containsExactly(new Material(Kind.INSTRUCTIONS, "🦀a"),
            new Material(Kind.PERSONAL_MEMORY, "乙丙"));
        assertThat(selected.proof())
            .isEqualTo(new Proof(PERSONAL, 20, 10, List.of(ENTRY), 4, null));
        assertThat(selected.toString()).doesNotContain("🦀a", "乙丙");
    }

    @Test
    void filedConversationNeverReadsPersonalInstructionsOrRevokedEntryBody() throws SQLException {
        scoped(FILED);
        controls("project", PROJECT, true, 10, 20);
        when(jdbc.queryForList(startsWith("SELECT id FROM assistant_memory_entry"),
            eq(String.class), eq(ACCOUNT), eq(WORKSPACE), eq("project"), eq(PROJECT), eq(100)))
            .thenReturn(List.of(ENTRY));
        when(journal.blocksMemoryEntry(ACCOUNT, WORKSPACE, ENTRY)).thenReturn(true);
        assertThat(adapter.select(RUN, LEASE, 100, 3)).isEmpty();
        verifiesNoBodyRead();
        assertThat(
            adapter.lockAndValidate(RUN, LEASE, new Proof(FILED, 0, 10, List.of(ENTRY), 8, null)))
            .isFalse();
    }

    @Test
    void missingMembershipAndMovedConversationBothInvalidateTheReadAndPublication() {
        scoped(FILED);
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(ACCOUNT), eq(WORKSPACE))).thenReturn(List.of());
        assertThat(adapter.select(RUN, LEASE, 100, 3)).isEmpty();
        assertThat(
            adapter.lockAndValidate(RUN, LEASE, new Proof(FILED, 0, 10, List.of(ENTRY), 8, null)))
            .isFalse();
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(ACCOUNT), eq(WORKSPACE))).thenReturn(List.of(ACCOUNT));
        scoped(PERSONAL);
        assertThat(
            adapter.lockAndValidate(RUN, LEASE, new Proof(FILED, 0, 10, List.of(ENTRY), 8, null)))
            .isFalse();
        verifiesNoBodyRead();
    }

    @Test
    void projectMovedBetweenInitialReadAndLocksIsRejectedBeforeReadingSettings() {
        when(jdbc.query(contains("FROM assistant_run r"), ArgumentMatchers.<RowMapper<Scope>>any(),
            eq(RUN), eq(LEASE), anyLong())).thenReturn(List.of(FILED), List.of(PERSONAL));
        assertThat(adapter.select(RUN, LEASE, 100, 3)).isEmpty();
        verify(jdbc, never()).query(startsWith("SELECT memory_enabled, "),
            ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        verifiesNoBodyRead();
    }

    @Test
    void changedProjectSourceAuthorityRejectsAnOtherwiseValidLease() {
        scoped(FILED);
        var access = new AssistantProjectMemorySources.Access("member", null, "old-placement");
        when(sources.current(FILED, access)).thenReturn(false);
        var proof = new Proof(FILED, 0, 10, List.of(ENTRY), 8, access);
        assertThat(adapter.lockAndValidate(RUN, LEASE, proof)).isFalse();
        verify(jdbc, never()).query(startsWith("SELECT memory_enabled, "),
            ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        verifiesNoBodyRead();
    }

    @Test
    void durableCutoffsOverrideStaleOptInBeforeReadingAndPublishing() throws SQLException {
        scoped(PERSONAL);
        controls("personal", WORKSPACE, true, 10, 20);
        when(journal.memoryRevocations(ACCOUNT, WORKSPACE, null))
            .thenReturn(new MemoryRevocations(10, 0, 20));
        assertThat(adapter.select(RUN, LEASE, 100, 3)).isEmpty();
        assertThat(
            adapter.lockAndValidate(RUN, LEASE, new Proof(PERSONAL, 20, 0, List.of(), 8, null)))
            .isFalse();
        verifiesNoBodyRead();
    }

    private void scoped(Scope scope) {
        when(jdbc.query(contains("FROM assistant_run r"), ArgumentMatchers.<RowMapper<Scope>>any(),
            eq(RUN), eq(LEASE), anyLong())).thenReturn(List.of(scope));
    }

    private void controls(String kind, String scopeId, boolean on, long memory, long instructions)
        throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBoolean("memory_enabled")).thenReturn(on);
        when(row.getLong("memory_epoch")).thenReturn(memory);
        when(row.getLong("instructions_epoch")).thenReturn(instructions);
        when(jdbc.query(startsWith("SELECT memory_enabled, "),
            ArgumentMatchers.<RowMapper<Object>>any(),
            eq(ACCOUNT), eq(WORKSPACE), eq(kind), eq(scopeId)))
            .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(row, 0)));
    }

    private void verifiesNoBodyRead() {
        verify(jdbc, never()).queryForList(
            startsWith("SELECT instructions FROM assistant_memory_settings"),
            eq(String.class), any(Object[].class));
        verify(jdbc, never()).queryForList(startsWith("SELECT content FROM assistant_memory_entry"),
            eq(String.class), any(Object[].class));
    }
}
