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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.Event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Checks invitation tombstones after restarting against older database state.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantReminderTemplateJournalTest {

    @TempDir
    private Path directory;

    @Test
    void usesDistinctScopedShareAndSourceTombstonesAcrossRecoveries() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("reminder-template.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String source = id();
        String retired = id();
        String available = id();
        Event revocation;
        AssistantDeletionJournal live =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            live.ready();
            assertThat(live.blocksReminderShare(owner, workspace, source, retired)).isFalse();
            revocation = live.revokeReminderShare(owner, workspace, retired);
            live.applied(revocation);
            assertThat(live.blocksReminderShare(owner, workspace, source, retired)).isTrue();
            assertThat(live.blocksReminderShare(owner, workspace, source, available)).isFalse();
        } finally {
            live.close();
        }

        AssistantDeletionJournal restored =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(revocation);
            restored.applied(revocation);
            restored.ready();
            assertThat(restored.blocksReminderShare(owner, workspace, source, retired)).isTrue();
            assertThat(restored.blocksReminderShare(owner, id(), source, retired)).isFalse();
            assertThat(restored.blocksReminderShare(id(), workspace, source, retired)).isFalse();
            assertThat(restored.blocksReminderShare(owner, workspace, source, available)).isFalse();
            restored.applied(restored.reminderDelete(owner, workspace, source));
            assertThat(restored.blocksReminderShare(owner, workspace, source, available)).isTrue();
        } finally {
            restored.close();
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
