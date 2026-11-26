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
import java.util.List;
import java.util.UUID;

/**
 * Verifies independent daily issuance, dismissal and preference retirement.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantProactiveJournalTest {

    @TempDir
    private Path directory;

    @Test
    void holdsRetiredAndDismissedSuggestionsAcrossAnEarlierDatabaseRestart() throws IOException {
        String binding = id();
        String owner = id();
        String workspace = id();
        String first = id();
        long epoch = 1_750_000_000_000L;
        Path path = Files.writeString(directory.resolve("local-proactive.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        AssistantDeletionJournal.Event disabled;
        var initial = new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            initial.ready();
            assertThat(initial.proactivePreferenceCutoff(owner, workspace)).isZero();
            assertThat(initial.proactiveSuggestionIssued(owner, workspace, first)).isFalse();
            var issued = initial.proactiveSuggestionIssue(owner, workspace, first);
            assertThat(initial.proactiveSuggestionIssued(owner, workspace, first)).isTrue();
            assertThat(initial.blocksAccount(owner)).isFalse();
            assertThat(initial.dispatchPaused()).isTrue();
            initial.applied(issued);
            disabled = initial.proactivePreferenceOff(owner, workspace, epoch);
            assertThat(disabled.createdAt()).isGreaterThanOrEqualTo(epoch);
            assertThat(initial.blocksAccount(owner)).isTrue();
            initial.applied(disabled);
            initial.applied(initial.proactiveSuggestionDismiss(owner, workspace, first));
        } finally {
            initial.close();
        }
        var restored = new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).hasSize(3);
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.proactivePreferenceCutoff(owner, workspace))
                .isEqualTo(disabled.createdAt());
            assertThat(restored.proactiveSuggestionIssued(owner, workspace, first)).isTrue();
            assertThat(restored.proactiveSuggestionDismissed(owner, workspace, first)).isTrue();
            assertThat(restored.proactivePreferenceCutoff(owner, id())).isZero();
            assertThat(restored.proactiveSuggestionIssued(id(), workspace, first)).isFalse();
        } finally {
            restored.close();
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
