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
import top.continew.admin.assistant.privacy.AssistantDeletionJournal.MemoryRevocations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Checks isolation and replay of independent memory privacy events.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantMemoryJournalTest {

    @TempDir
    private Path directory;

    @Test
    void preservesIndependentRevocationCutoffsAndEntryErasureWhenReopened() throws IOException {
        String token = id();
        Path path = Files.writeString(directory.resolve("memory.journal"),
            "assistant-deletion-journal-v1 " + token + "\n");
        String owner = id();
        String foreign = id();
        String workspace = id();
        String project = id();
        String sibling = id();
        String entry = id();
        long epoch = System.currentTimeMillis() + 100_000;
        List<Event> events;
        AssistantDeletionJournal live = open(path, token);
        try {
            live.ready();
            Event manual = live.memoryOff(owner, workspace, null, epoch);
            Event projectMemory = live.memoryOff(owner, workspace, project, epoch + 1);
            Event history = live.historyOff(owner, workspace, epoch + 2);
            Event instructions = live.instructionsOff(owner, workspace, epoch + 3);
            events = List.of(manual, projectMemory, history, instructions,
                live.memoryEntry(owner, workspace, entry));
            assertThat(live.memoryRevocations(owner, workspace, null))
                .isEqualTo(new MemoryRevocations(epoch, epoch + 2, epoch + 3));
            assertThat(live.memoryRevocations(owner, workspace, project).memory())
                .isEqualTo(epoch + 1);
            assertThat(live.memoryRevocations(owner, workspace, sibling).memory()).isZero();
            assertThat(live.memoryRevocations(foreign, workspace, null))
                .isEqualTo(new MemoryRevocations(0, 0, 0));
            assertThat(live.blocksMemoryEntry(owner, workspace, entry)).isTrue();
            assertThat(live.blocksMemoryEntry(owner, id(), entry)).isFalse();
            assertThat(live.blocksMemoryEntry(foreign, workspace, entry)).isFalse();
            assertThat(live.blocksMemoryEntry(owner, workspace, id())).isFalse();
            events.forEach(live::applied);
        } finally {
            live.close();
        }

        AssistantDeletionJournal restored = open(path, token);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactlyElementsOf(events);
            assertThat(restored.blocksMemoryEntry(foreign, workspace, entry)).isTrue();
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.memoryRevocations(owner, workspace, null))
                .isEqualTo(new MemoryRevocations(epoch, epoch + 2, epoch + 3));
            assertThat(restored.memoryRevocations(owner, workspace, project).memory())
                .isEqualTo(epoch + 1);
            assertThat(restored.memoryRevocations(owner, workspace, sibling).memory()).isZero();
            assertThat(restored.blocksMemoryEntry(owner, workspace, entry)).isTrue();
            assertThat(restored.blocksMemoryEntry(foreign, workspace, entry)).isFalse();
            restored.applied(restored.account(owner));
            assertThat(restored.memoryRevocations(owner, workspace, project))
                .isEqualTo(new MemoryRevocations(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
            assertThat(restored.blocksMemoryEntry(owner, workspace, id())).isTrue();
        } finally {
            restored.close();
        }
    }

    private static AssistantDeletionJournal open(Path path, String token) {
        return new AssistantDeletionJournal(true, path.toString(), token);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
