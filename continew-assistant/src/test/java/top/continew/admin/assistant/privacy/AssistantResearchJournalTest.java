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
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.Kind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Tests scoped research approval revocation during replay and startup.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantResearchJournalTest {

    @TempDir
    private Path directory;

    @Test
    void reopeningOlderStateRetainsOnlyTheExactApprovalCutoff() throws IOException {
        String journalId = id();
        Path path = Files.writeString(directory.resolve("research.journal"),
            "assistant-deletion-journal-v1 " + journalId + "\n");
        String owner = id();
        String workspace = id();
        String task = id();
        long oldEpoch = System.currentTimeMillis() + 100_000;
        Event cutoff;
        AssistantDeletionJournal live = open(path, journalId);
        try {
            live.ready();
            assertThat(live.researchCutoff(owner, workspace, task)).isZero();
            cutoff = live.revokeResearch(owner, workspace, task, oldEpoch);
            live.applied(cutoff);
            assertThat(cutoff.kind()).isEqualTo(Kind.RESEARCH_REVOKE);
            assertThat(live.researchCutoff(owner, workspace, task)).isEqualTo(oldEpoch);
            assertThat(live.researchCutoff(owner, workspace, id())).isZero();
        } finally {
            live.close();
        }

        AssistantDeletionJournal restored = open(path, journalId);
        try {
            assertThat(restored.researchCutoff(owner, workspace, task)).isEqualTo(Long.MAX_VALUE);
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(cutoff);
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.researchCutoff(owner, workspace, task)).isEqualTo(oldEpoch);
            assertThat(restored.researchCutoff(owner, id(), task)).isZero();
            assertThat(restored.researchCutoff(id(), workspace, task)).isZero();
            restored.applied(restored.account(owner));
            assertThat(restored.researchCutoff(owner, workspace, task)).isEqualTo(Long.MAX_VALUE);
        } finally {
            restored.close();
        }
    }

    private static AssistantDeletionJournal open(Path path, String journalId) {
        return new AssistantDeletionJournal(true, path.toString(), journalId);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
