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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies independent reminder pause and revocation lookups across database restores.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantReminderJournalTest {

    @TempDir
    private Path directory;

    @Test
    void reloadsPauseAndPermanentCancellationAsSeparateScopedControls() throws IOException {
        String owner = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        String outsider = UUID.randomUUID().toString();
        String paused = UUID.randomUUID().toString();
        String cancelled = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        Path file = directory.resolve("journal");
        Files.writeString(file, "assistant-deletion-journal-v1 " + id + "\n");

        long pauseEpoch;
        var live = new AssistantDeletionJournal(true, file.toString(), id);
        try {
            live.ready();
            var pause = live.reminderPause(owner, workspace, paused, 17L);
            pauseEpoch = pause.createdAt();
            assertThat(pauseEpoch).isGreaterThan(17);
            live.applied(pause);
            var cancellation = live.reminderDelete(owner, workspace, cancelled);
            live.applied(cancellation);
            assertThat(live.blocksReminder(owner, workspace, paused)).isFalse();
            assertThat(live.blocksReminder(owner, workspace, cancelled)).isTrue();
        } finally {
            live.close();
        }

        var restored = new AssistantDeletionJournal(true, file.toString(), id);
        try {
            assertThat(restored.blocksReminder(owner, workspace, paused)).isTrue();
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.reminderPauseCutoff(owner, workspace, paused))
                .isEqualTo(pauseEpoch);
            assertThat(restored.blocksReminder(owner, workspace, paused)).isFalse();
            assertThat(restored.blocksReminder(owner, workspace, cancelled)).isTrue();
            assertThat(restored.blocksReminder(outsider, workspace, cancelled)).isFalse();
            assertThat(restored.reminderPauseCutoff(outsider, workspace, paused)).isZero();
        } finally {
            restored.close();
        }
    }
}
