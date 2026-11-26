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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.admin.assistant.account.AssistantApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Tests independent deletion journal durability and fail-closed validation.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantDeletionJournalTest {

    private static final String ACCOUNT = "f45065fc-5b7e-4fe4-a083-b0f1d0f7d039";
    private static final String OTHER = "c7fdfcd6-a15a-40d9-94a5-ed3d71394b56";
    private static final String WORKSPACE = "57f16978-1bc0-4afe-a1f6-eb95bf06e3f6";
    private static final String CONVERSATION = "3820d910-3b44-4a01-84af-b1d6e1019c86";
    private static final String SHARE = "269ba3a4-649f-48a0-a64c-d0786cf79424";
    private static final String RESOURCE = "82c1013b-65b9-438a-ac2e-a3ae0f01436b";

    @TempDir
    private Path directory;

    @Test
    void disabledFeaturePreservesOrdinaryAccountAccess() {
        AssistantDeletionJournal journal = new AssistantDeletionJournal(false, "", "");
        assertThat(journal.blocksAccount(ACCOUNT)).isFalse();
        assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, SHARE)).isTrue();
        assertThat(journal.dispatchPaused()).isFalse();
        assertThatThrownBy(() -> journal.account(ACCOUNT))
            .isInstanceOfSatisfying(AssistantApiException.class,
                failure -> assertThat(failure.status().value()).isEqualTo(403));
    }

    @Test
    void keepsShareRevocationEffectiveBeforeCommitAndAfterOlderDatabaseReplay() throws IOException {
        Fixture fixture = fixture();
        AssistantDeletionJournal.Event event;
        AssistantDeletionJournal journal = fixture.open();
        try {
            assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, SHARE)).isTrue();
            journal.ready();
            assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, SHARE)).isFalse();
            event = journal.share(ACCOUNT, WORKSPACE, SHARE);
            assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, SHARE)).isTrue();
            assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, OTHER)).isFalse();
            journal.applied(event);
            assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, SHARE)).isTrue();
        } finally {
            journal.close();
        }
        AssistantDeletionJournal restored = fixture.open();
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(event);
            assertThat(restored.blocksShare(OTHER, WORKSPACE, CONVERSATION, SHARE)).isTrue();
            restored.applied(event);
            restored.ready();
            assertThat(restored.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, SHARE)).isTrue();
            assertThat(restored.blocksShare(OTHER, WORKSPACE, CONVERSATION, SHARE)).isFalse();
        } finally {
            restored.close();
        }
    }

    @Test
    void opensPreviousFormatAndUsesSourceDeletionToDenyOutstandingShares() throws IOException {
        String id = "91b80167-8051-4cf7-ae03-8797e25a301e";
        Path path = directory.resolve("existing-v1.journal");
        String previous = "3f2762d019351ef18520f1c22516099256a075c0b9f3466ef838a74b1f034714";
        String eventId = "6cba71912f657505d6128f168ea8592a6675d79d8bcf5146fa7cbef98ddb296f";
        Files.writeString(path, "assistant-deletion-journal-v1 " + id + "\n"
            + String.join(":", "CONVERSATION", ACCOUNT, WORKSPACE, CONVERSATION, "1700000000000",
                previous, eventId)
            + "\n");
        AssistantDeletionJournal journal = new AssistantDeletionJournal(true, path.toString(), id);
        try {
            journal.verify(List.of(eventId));
            journal.pending().forEach(journal::applied);
            journal.ready();
            assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, CONVERSATION, SHARE)).isTrue();
            assertThat(journal.blocksShare(ACCOUNT, WORKSPACE, OTHER, SHARE)).isFalse();
            var account = journal.account(OTHER);
            journal.applied(account);
            assertThat(journal.blocksShare(OTHER, WORKSPACE, CONVERSATION, SHARE)).isTrue();
        } finally {
            journal.close();
        }
    }

    @Test
    void preservesOwnerScopedMaterialRevocationAfterOpeningAnOlderDatabase() throws IOException {
        Fixture fixture = fixture();
        AssistantDeletionJournal.Event resource;
        AssistantDeletionJournal journal = fixture.open();
        try {
            assertThat(journal.blocksProjectResource(ACCOUNT, WORKSPACE, RESOURCE)).isTrue();
            journal.ready();
            assertThat(journal.blocksProjectResource(ACCOUNT, WORKSPACE, RESOURCE)).isFalse();
            resource = journal.projectResource(ACCOUNT, WORKSPACE, RESOURCE);
            assertThat(journal.blocksProjectResource(ACCOUNT, WORKSPACE, RESOURCE)).isTrue();
            journal.applied(resource);
            assertThat(journal.blocksProjectResource(ACCOUNT, WORKSPACE, RESOURCE)).isTrue();
        } finally {
            journal.close();
        }
        AssistantDeletionJournal restored = fixture.open();
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(resource);
            restored.applied(resource);
            restored.ready();
            assertThat(restored.blocksProjectResource(ACCOUNT, WORKSPACE, RESOURCE)).isTrue();
            assertThat(restored.blocksProjectResource(OTHER, WORKSPACE, RESOURCE)).isFalse();
            assertThat(restored.blocksProjectResource(ACCOUNT, OTHER, RESOURCE)).isFalse();
            assertThat(restored.blocksProjectResource(ACCOUNT, WORKSPACE, OTHER)).isFalse();
            restored.applied(restored.account(ACCOUNT));
            assertThat(restored.blocksProjectResource(ACCOUNT, WORKSPACE, OTHER)).isTrue();
        } finally {
            restored.close();
        }
    }

    @Test
    void persistsPendingIntentsAndGatesAccountAccessUntilReplayCommits() throws IOException {
        Fixture fixture = fixture();
        AssistantDeletionJournal journal = fixture.open();
        var events = List.<AssistantDeletionJournal.Event>of();
        try {
            assertThat(journal.blocksAccount(ACCOUNT)).isTrue();
            journal.verify(List.of());
            journal.ready();
            assertThat(journal.blocksAccount(ACCOUNT)).isFalse();
            var one = journal.conversation(ACCOUNT, WORKSPACE, CONVERSATION);
            var two = journal.account(ACCOUNT);
            events = List.of(one, two);
            assertThat(journal.blocksAccount(ACCOUNT)).isTrue();
            assertThat(journal.blocksAccount(OTHER)).isFalse();
            assertThat(journal.dispatchPaused()).isTrue();
            journal.applied(one);
            assertThat(journal.blocksAccount(ACCOUNT)).isTrue();
        } finally {
            journal.close();
        }
        AssistantDeletionJournal restored = fixture.open();
        try {
            restored.verify(List.of(events.get(0).id()));
            assertThat(restored.pending()).containsExactlyElementsOf(events);
            assertThatIllegalStateException().isThrownBy(restored::ready);
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.dispatchPaused()).isFalse();
        } finally {
            restored.close();
        }
    }

    @Test
    void refusesConcurrentWritersAndMissingJournalEvidence() throws IOException {
        Fixture fixture = fixture();
        AssistantDeletionJournal journal = fixture.open();
        try {
            assertThatIllegalStateException().isThrownBy(fixture::open);
            assertThatIllegalStateException()
                .isThrownBy(() -> journal.verify(List.of("lost-record")));
        } finally {
            journal.close();
        }
    }

    @Test
    void rejectsWrongIdentityAndAnInterruptedAppend() throws IOException {
        Fixture fixture = fixture();
        assertThatIllegalStateException().isThrownBy(() -> new AssistantDeletionJournal(true,
            fixture.path().toString(), UUID.randomUUID().toString()));
        Files.writeString(fixture.path(), "interrupted", StandardOpenOption.APPEND);
        assertThatIllegalStateException().isThrownBy(fixture::open);
    }

    @Test
    void refusesAForgedChecksum() throws IOException {
        Fixture fixture = fixture();
        AssistantDeletionJournal journal = fixture.open();
        try {
            journal.ready();
            journal.account(ACCOUNT);
        } finally {
            journal.close();
        }
        String contents = Files.readString(fixture.path());
        Files.writeString(fixture.path(), contents.replace(ACCOUNT, OTHER));
        assertThatIllegalStateException().isThrownBy(fixture::open);
    }

    private Fixture fixture() throws IOException {
        String id = UUID.randomUUID().toString();
        Path path = directory.resolve("privacy.journal");
        Files.writeString(path, "assistant-deletion-journal-v1 " + id + "\n");
        return new Fixture(path, id);
    }

    @Test
    void blocksStaleSettingsAndPersistentMemoryOrAttachmentRevocationsInExports()
        throws IOException {
        Fixture fixture = fixture();
        AssistantDeletionJournal journal = fixture.open();
        try {
            journal.ready();
            var policy = new AssistantDeletionJournal.ExportMemoryPolicy(null,
                journal.memoryRevocations(ACCOUNT, WORKSPACE, null));
            assertThat(journal.allowsExportSnapshot(ACCOUNT, WORKSPACE, List.of(), List.of(),
                List.of(RESOURCE), List.of(SHARE), List.of(policy))).isTrue();

            var change = journal.instructionsOff(ACCOUNT, WORKSPACE, 10L);
            journal.applied(change);
            assertThat(journal.allowsExportSnapshot(ACCOUNT, WORKSPACE, List.of(), List.of(),
                List.of(), List.of(), List.of(policy))).isFalse();

            var memory = journal.memoryEntry(ACCOUNT, WORKSPACE, RESOURCE);
            journal.applied(memory);
            assertThat(journal.allowsExportSnapshot(ACCOUNT, WORKSPACE, List.of(), List.of(),
                List.of(RESOURCE), List.of(), List.of())).isFalse();
            var attachment = journal.attachment(ACCOUNT, WORKSPACE, SHARE);
            journal.applied(attachment);
            assertThat(journal.allowsExportSnapshot(ACCOUNT, WORKSPACE, List.of(), List.of(),
                List.of(), List.of(SHARE), List.of())).isFalse();
            assertThat(journal.allowsExportSnapshot(OTHER, WORKSPACE, List.of(), List.of(),
                List.of(RESOURCE), List.of(SHARE), List.of())).isTrue();
        } finally {
            journal.close();
        }
    }

    private record Fixture(Path path, String id) {

        private AssistantDeletionJournal open() {
            return new AssistantDeletionJournal(true, path.toString(), id);
        }
    }
}
