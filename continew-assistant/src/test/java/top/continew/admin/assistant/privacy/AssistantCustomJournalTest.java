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
 * Verifies scoped custom-definition cutoffs and deletion across journal reopen.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantCustomJournalTest {

    @TempDir
    private Path directory;

    @Test
    void recordsRevisionAndTombstoneIndependentlyOfTheDatabaseSnapshot() throws IOException {
        String owner = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        String retained = UUID.randomUUID().toString();
        String removed = UUID.randomUUID().toString();
        String identity = UUID.randomUUID().toString();
        Path path = directory.resolve("privacy.journal");
        Files.writeString(path, "assistant-deletion-journal-v1 " + identity + "\n");

        long cutoff;
        var live = new AssistantDeletionJournal(true, path.toString(), identity);
        try {
            live.ready();
            var revise = live.customAssistantRevision(owner, workspace, retained, 100L);
            cutoff = revise.createdAt();
            assertThat(cutoff).isGreaterThan(100L);
            live.applied(revise);
            live.applied(live.customAssistantDelete(owner, workspace, removed));
        } finally {
            live.close();
        }

        var restored = new AssistantDeletionJournal(true, path.toString(), identity);
        try {
            assertThat(restored.blocksCustomAssistant(owner, workspace, retained)).isTrue();
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.customAssistantCutoff(owner, workspace, retained))
                .isEqualTo(cutoff);
            assertThat(restored.blocksCustomAssistant(owner, workspace, retained)).isFalse();
            assertThat(restored.blocksCustomAssistant(owner, workspace, removed)).isTrue();
            assertThat(restored.blocksCustomAssistant(owner, retained, removed)).isFalse();
        } finally {
            restored.close();
        }
    }
}
