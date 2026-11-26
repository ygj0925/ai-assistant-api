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
 * Checks the independent project scopes consumed by live readers and database replay.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantProjectCollaborationJournalTest {

    @TempDir
    private Path directory;

    @Test
    void keepsDeclinedGrantAndRemovedTextClosedAfterRestart() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("collaboration.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String retired = id();
        String reopened = id();
        Event grant;
        Event text;
        AssistantDeletionJournal live =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            live.ready();
            assertThat(live.blocksCollaborationGrant(owner, workspace, retired)).isFalse();
            grant = live.revokeCollaborationGrant(owner, workspace, retired);
            live.applied(grant);
            assertThat(live.blocksCollaborationText(owner, workspace, retired)).isFalse();
            text = live.deleteCollaborationText(owner, workspace, retired);
            live.applied(text);
        } finally {
            live.close();
        }

        AssistantDeletionJournal restored = new AssistantDeletionJournal(true, path.toString(),
            binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(grant, text);
            restored.applied(grant);
            restored.applied(text);
            restored.ready();
            assertThat(restored.blocksCollaborationGrant(owner, workspace, retired)).isTrue();
            assertThat(restored.blocksCollaborationText(owner, workspace, retired)).isTrue();
            assertThat(restored.blocksCollaborationGrant(owner, workspace, reopened)).isFalse();
            assertThat(restored.blocksCollaborationText(owner, id(), retired)).isFalse();
            assertThat(restored.blocksCollaborationGrant(id(), workspace, retired)).isFalse();
            restored.applied(restored.account(owner));
            assertThat(restored.blocksCollaborationParticipant(owner)).isTrue();
            assertThat(restored.blocksCollaborationGrant(owner, workspace, reopened)).isTrue();
            assertThat(restored.blocksCollaborationText(owner, workspace, reopened)).isTrue();
        } finally {
            restored.close();
        }
    }

    @Test
    void neverReopensAnEarlierPublicationWhenConversationIsUnlocked() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("publication.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String conversation = id();
        String publication = id();
        Event lock;
        Event unlock;
        Event revoked;
        AssistantDeletionJournal live =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            live.ready();
            assertThat(live.blocksArtifactPublicationPolicy(owner, workspace, conversation, null))
                .isFalse();
            lock = live.changeSharingRestriction(owner, workspace, conversation, true);
            live.applied(lock);
            assertThat(
                live.blocksArtifactPublicationPolicy(owner, workspace, conversation, lock.id()))
                .isTrue();
            unlock = live.changeSharingRestriction(owner, workspace, conversation, false);
            live.applied(unlock);
            assertThat(live.blocksArtifactPublicationPolicy(owner, workspace, conversation, null))
                .isTrue();
            assertThat(
                live.blocksArtifactPublicationPolicy(owner, workspace, conversation, unlock.id()))
                .isFalse();
            revoked = live.revokeArtifactPublication(owner, workspace, publication);
            live.applied(revoked);
        } finally {
            live.close();
        }

        AssistantDeletionJournal restored = new AssistantDeletionJournal(true, path.toString(),
            binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(lock, unlock, revoked);
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.blocksArtifactPublication(owner, workspace, publication)).isTrue();
            assertThat(restored.blocksArtifactPublication(owner, workspace, id())).isFalse();
            assertThat(
                restored.blocksArtifactPublicationPolicy(owner, workspace, conversation, null))
                .isTrue();
            assertThat(restored.blocksArtifactPublicationPolicy(owner, workspace, conversation,
                unlock.id()))
                .isFalse();
        } finally {
            restored.close();
        }
    }

    @Test
    void refusesAnErasedCommentRetryAfterRecoveringAnOlderDatabase() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("comment.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String removed = id();
        Event event;
        AssistantDeletionJournal live =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            live.ready();
            assertThat(live.blocksArtifactComment(owner, workspace, removed)).isFalse();
            event = live.deleteArtifactComment(owner, workspace, removed);
            live.applied(event);
            assertThat(live.blocksArtifactComment(owner, workspace, removed)).isTrue();
            assertThat(live.blocksArtifactPublication(owner, workspace, removed)).isFalse();
        } finally {
            live.close();
        }
        AssistantDeletionJournal restored = new AssistantDeletionJournal(true, path.toString(),
            binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(event);
            restored.applied(event);
            restored.ready();
            assertThat(restored.blocksArtifactComment(owner, workspace, removed)).isTrue();
            assertThat(restored.blocksArtifactComment(owner, id(), removed)).isFalse();
            assertThat(restored.blocksArtifactComment(id(), workspace, removed)).isFalse();
            assertThat(restored.blocksArtifactComment(owner, workspace, id())).isFalse();
        } finally {
            restored.close();
        }
    }

    @Test
    void checksDraftAndContributorRetirementIndependentlyAfterRecovery() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("draft.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String retired = id();
        String author = id();
        Event removed;
        Event erased;
        AssistantDeletionJournal live =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            live.ready();
            removed = live.deleteSharedDraft(owner, workspace, retired);
            live.applied(removed);
            erased = live.account(author);
            live.applied(erased);
        } finally {
            live.close();
        }
        AssistantDeletionJournal restored = new AssistantDeletionJournal(true, path.toString(),
            binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(removed, erased);
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.blocksSharedDraft(owner, workspace, retired)).isTrue();
            assertThat(restored.blocksSharedDraft(owner, id(), retired)).isFalse();
            assertThat(restored.blocksSharedDraft(owner, workspace, id())).isFalse();
            assertThat(restored.blocksCollaborationParticipant(author)).isTrue();
            assertThat(restored.blocksCollaborationParticipant(owner)).isFalse();
        } finally {
            restored.close();
        }
    }

    @Test
    void deniesOnlyTheDepartedWorkspaceGenerationAfterRecovery() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("workspace.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String member = id();
        String workspace = id();
        String earlier = "00000000-0000-0000-0000-000000000000";
        String renewed = id();
        Event departed;
        AssistantDeletionJournal live =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            live.ready();
            departed = live.leaveWorkspace(member, workspace, earlier);
            assertThat(live.blocksWorkspaceMembership(member, workspace, earlier)).isTrue();
            assertThat(live.blocksWorkspaceMembership(member, workspace, renewed)).isFalse();
            live.applied(departed);
        } finally {
            live.close();
        }
        AssistantDeletionJournal restored = new AssistantDeletionJournal(true, path.toString(),
            binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(departed);
            restored.applied(departed);
            restored.ready();
            assertThat(restored.blocksWorkspaceMembership(member, workspace, earlier)).isTrue();
            assertThat(restored.blocksWorkspaceMembership(member, workspace, renewed)).isFalse();
            assertThat(restored.blocksWorkspaceMembership(member, id(), earlier)).isFalse();
            assertThat(restored.blocksWorkspaceMembership(id(), workspace, earlier)).isFalse();
            assertThat(restored.blocksAccount(member)).isFalse();
        } finally {
            restored.close();
        }
    }

    @Test
    void requiresTheNewestPasswordOpenedReadGenerationEvenAfterRestart() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("conversation-read-lock.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String conversation = id();
        String share = id();
        String artifact = id();
        Event locked;
        Event opened;
        AssistantDeletionJournal live =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            live.ready();
            assertThat(live.blocksConversationRead(owner, workspace, conversation, null)).isFalse();
            locked = live.changeConversationReadLock(owner, workspace, conversation, true);
            live.applied(locked);
            assertThat(live.blocksConversationRead(owner, workspace, conversation, locked.id()))
                .isTrue();
            assertThat(live.blocksShare(owner, workspace, conversation, share)).isTrue();
            assertThat(live.blocksArtifact(owner, workspace, artifact, conversation)).isTrue();
            opened = live.changeConversationReadLock(owner, workspace, conversation, false);
            live.applied(opened);
        } finally {
            live.close();
        }
        AssistantDeletionJournal restored = new AssistantDeletionJournal(true, path.toString(),
            binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(locked, opened);
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.blocksConversationRead(owner, workspace, conversation, null))
                .isTrue();
            assertThat(restored.blocksConversationRead(owner, workspace, conversation, locked.id()))
                .isTrue();
            assertThat(restored.blocksConversationRead(owner, workspace, conversation, opened.id()))
                .isFalse();
            assertThat(restored.blocksConversationRead(owner, workspace, id(), null)).isFalse();
            assertThat(restored.conversationReadFence(owner, workspace)).isEqualTo(opened.id());
            assertThat(restored.conversationReadFence(owner, id())).isNull();
        } finally {
            restored.close();
        }
    }

    @Test
    void maintainsSourcePlacementGenerationIndependentlyOfConversationReadPolicy()
        throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("history-placement.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String conversation = id();
        Event movedAway;
        Event returned;
        AssistantDeletionJournal journal =
            new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            journal.ready();
            assertThat(journal.historyPlacementRevision(owner, workspace, conversation)).isNull();
            movedAway = journal.fenceHistoryPlacement(owner, workspace, conversation);
            journal.applied(movedAway);
            returned = journal.fenceHistoryPlacement(owner, workspace, conversation);
            journal.applied(returned);
            assertThat(journal.historyPlacementRevision(owner, workspace, conversation))
                .isEqualTo(returned.id()).isNotEqualTo(movedAway.id());
        } finally {
            journal.close();
        }
        AssistantDeletionJournal restored = new AssistantDeletionJournal(true, path.toString(),
            binding);
        try {
            restored.verify(List.of());
            assertThat(restored.pending()).containsExactly(movedAway, returned);
            restored.pending().forEach(restored::applied);
            restored.ready();
            assertThat(restored.historyPlacementRevision(owner, workspace, conversation))
                .isEqualTo(returned.id());
            assertThat(restored.historyPlacementRevision(owner, workspace, id())).isNull();
            assertThat(restored.historyPlacementRevision(owner, id(), conversation)).isNull();
            assertThat(restored.blocksConversationRead(owner, workspace, conversation, null))
                .isFalse();
        } finally {
            restored.close();
        }
    }

    @Test
    void recordingConnectorDispatchPreservesLoginWhileBlockingDuplicateWork() throws IOException {
        String binding = id();
        Path path = Files.writeString(directory.resolve("connector-start.journal"),
            "assistant-deletion-journal-v1 " + binding + "\n");
        String owner = id();
        String workspace = id();
        String action = id();
        String authorization = id();
        var journal = new AssistantDeletionJournal(true, path.toString(), binding);
        try {
            journal.ready();
            var started =
                journal.sandboxConnectorEvent(AssistantDeletionJournal.Kind.CONNECTOR_START,
                    owner, workspace, action);
            assertThat(journal.blocksAccount(owner)).isFalse();
            assertThat(journal.sandboxActionStarted(owner, workspace, action)).isTrue();
            assertThat(journal.dispatchPaused()).isTrue();
            journal.applied(started);

            var cancelled =
                journal.sandboxConnectorEvent(AssistantDeletionJournal.Kind.CONNECTOR_CANCEL,
                    owner, workspace, id());
            assertThat(journal.blocksAccount(owner)).isTrue();
            journal.applied(cancelled);
            var revoked =
                journal.sandboxConnectorEvent(AssistantDeletionJournal.Kind.CONNECTOR_REVOKE,
                    owner, workspace, authorization);
            assertThat(journal.blocksAccount(owner)).isTrue();
            assertThat(journal.sandboxAuthorizationRevoked(owner, workspace, authorization))
                .isTrue();
            journal.applied(revoked);
        } finally {
            journal.close();
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
