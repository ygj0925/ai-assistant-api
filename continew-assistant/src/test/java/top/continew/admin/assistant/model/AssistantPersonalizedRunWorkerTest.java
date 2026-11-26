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

package top.continew.admin.assistant.model;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.memory.AssistantGenerationMemory;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Kind;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Material;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Proof;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Scope;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Selection;
import top.continew.admin.assistant.model.AssistantModels.Model;
import top.continew.admin.assistant.model.AssistantModels.Turn;
import top.continew.admin.assistant.observability.AssistantOperations;
import top.continew.admin.assistant.observability.AssistantOperations.Generation;
import top.continew.admin.assistant.observability.AssistantOperations.Outcome;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.run.AssistantRunContext;
import top.continew.admin.assistant.run.AssistantRuns;
import top.continew.admin.assistant.run.AssistantRuns.Claim;
import top.continew.admin.assistant.run.AssistantRuns.FinishOutcome;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Checks that source consent is validated before the worker calls a model.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantPersonalizedRunWorkerTest {

    private static final Model MODEL = new Model("local-echo", "synthetic-v1");
    private static final List<Turn> BRANCH = List.of(new Turn("user", "request"));
    private static final Claim CLAIM = new Claim("run", "lease", MODEL.id(), MODEL.version(),
        BRANCH, "account", "workspace", new AssistantRunContext(32_768, 7, 1, false), null, false);
    private static final Claim HISTORICAL = new Claim(CLAIM.runId(), CLAIM.leaseToken(), MODEL.id(),
        MODEL.version(), BRANCH, CLAIM.accountId(), CLAIM.workspaceId(), CLAIM.context(), null,
        true);
    private static final Proof PROOF = new Proof(new Scope("account", "workspace", "conversation",
        null), 21, 0, List.of(), 9, null);
    private static final Selection AVAILABLE = new Selection(
        List.of(new Material(Kind.INSTRUCTIONS, "synthetic")), PROOF);

    private final AssistantRuns runs = mock(AssistantRuns.class);
    private final AssistantModels models = mock(AssistantModels.class);
    private final AssistantOperations operations = mock(AssistantOperations.class);
    private final Generation generation = mock(Generation.class);
    private final AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
    private final AssistantGenerationMemory memory = mock(AssistantGenerationMemory.class);
    private final AssistantRunWorker worker = new AssistantRunWorker(runs, models, operations,
        journal, memory);

    @BeforeEach
    void readyToClaim() {
        when(runs.claimNext()).thenReturn(Optional.of(CLAIM));
        when(runs.mayGenerate(CLAIM.runId(), CLAIM.leaseToken())).thenReturn(true);
        when(models.require(CLAIM.modelId())).thenReturn(MODEL);
        when(operations.startGeneration(CLAIM.runId(), CLAIM.modelId())).thenReturn(generation);
    }

    @AfterEach
    void close() {
        worker.close();
    }

    @Test
    void optOutAfterSelectionCancelsBeforeModelOrPublicOutput() {
        when(models.supportsLocalMemory(MODEL)).thenReturn(true);
        when(memory.configured()).thenReturn(true);
        when(memory.select(CLAIM.runId(), CLAIM.leaseToken(), 32_761, 31))
            .thenReturn(Optional.of(AVAILABLE));
        when(memory.lockAndValidate(CLAIM.runId(), CLAIM.leaseToken(), PROOF)).thenReturn(false);
        when(runs.cancelClaim(CLAIM.runId(), CLAIM.leaseToken()))
            .thenReturn(FinishOutcome.CANCELLED);
        worker.tick();
        verify(generation, timeout(2_000)).finish(Outcome.CANCELLED);
        verify(models, never()).generateWithLocalMemory(any(), anyList(), anyList(), any());
        verify(models, never()).generate(any(), anyList(), any());
        verify(runs, never()).append(any(), any(), any());
    }

    @Test
    void losingConversationDuringEmptySelectionDoesNotDispatchPlainFallback() {
        when(models.supportsLocalMemory(MODEL)).thenReturn(true);
        when(memory.configured()).thenReturn(true);
        when(runs.mayGenerate(CLAIM.runId(), CLAIM.leaseToken())).thenReturn(true, false);
        worker.tick();
        verify(generation, timeout(2_000)).finish(Outcome.LEASE_LOST);
        verify(models, never()).generate(any(), anyList(), any());
    }

    @Test
    void ordinaryGenerationRetainsExistingStreamingPathWithoutSourceLookup() {
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(2).accept("ordinary");
            return null;
        }).when(models).generate(eq(CLAIM.modelId()), eq(BRANCH), any());
        when(runs.append(CLAIM.runId(), CLAIM.leaseToken(), "ordinary")).thenReturn(true);
        when(runs.finish(CLAIM.runId(), CLAIM.leaseToken())).thenReturn(FinishOutcome.COMPLETED);
        worker.tick();
        verify(generation, timeout(2_000)).finish(Outcome.COMPLETED);
        verify(runs).append(CLAIM.runId(), CLAIM.leaseToken(), "ordinary");
        verifyNoInteractions(memory);
    }

    @Test
    void withdrawnExplicitHistoryDoesNotFallBackToOrdinaryGeneration() {
        when(runs.claimNext()).thenReturn(Optional.of(HISTORICAL));
        when(models.supportsLocalMemory(MODEL)).thenReturn(true);
        when(runs.prepareHistorical(HISTORICAL)).thenReturn(Optional.empty());
        when(runs.cancelClaim(CLAIM.runId(), CLAIM.leaseToken()))
            .thenReturn(FinishOutcome.CANCELLED);
        worker.tick();
        verify(generation, timeout(2_000)).finish(Outcome.CANCELLED);
        verify(models, never()).generate(any(), anyList(), any());
        verify(models, never()).generateWithLocalMemory(any(), anyList(), anyList(), any());
        verifyNoInteractions(memory);
    }

    @Test
    void keepsHistoryOutputBufferedUntilItsFinalPolicyFence() {
        when(runs.claimNext()).thenReturn(Optional.of(HISTORICAL));
        when(models.supportsLocalMemory(MODEL)).thenReturn(true);
        var material = new Material(Kind.HISTORY_REPLY, "🦀字");
        when(runs.prepareHistorical(HISTORICAL)).thenReturn(Optional.of(material));
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(3).accept("buffered");
            return null;
        }).when(models).generateWithLocalMemory(eq(MODEL), eq(BRANCH), eq(List.of(material)),
            any());
        when(runs.publishHistorical(HISTORICAL, 2, "buffered")).thenReturn(FinishOutcome.CANCELLED);
        worker.tick();
        verify(generation, timeout(2_000)).finish(Outcome.CANCELLED);
        verify(runs).publishHistorical(HISTORICAL, 2, "buffered");
        verify(runs, never()).append(any(), any(), any());
        verifyNoInteractions(memory);
    }
}
