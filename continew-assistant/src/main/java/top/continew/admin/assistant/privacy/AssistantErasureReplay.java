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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Replays independent privacy intents before the web server and background scheduler start.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@Slf4j
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantErasureReplay implements SmartInitializingSingleton {

    private final AssistantDeletionJournal journal;
    private final AssistantErasure erasure;
    private boolean replayWarning;

    /**
     * Constructs the startup replay gate.
     *
     * @param journal local writer's durable intent store
     * @param erasure primary-store replay transaction
     */
    public AssistantErasureReplay(AssistantDeletionJournal journal, AssistantErasure erasure) {
        this.journal = journal;
        this.erasure = erasure;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> applied = erasure.appliedIds();
        if (!journal.enabled()) {
            erasure.verifyJournalBinding(null);
            if (!applied.isEmpty()) {
                throw new IllegalStateException("A deletion journal is required by this database.");
            }
            return;
        }
        journal.verify(applied);
        erasure.verifyJournalBinding(journal.identity());
        journal.pending().forEach(erasure::replay);
        journal.ready();
    }

    /**
     * Retries durable intents whose primary database transaction was interrupted.
     */
    @Scheduled(fixedDelay = 3000)
    public void retryUnapplied() {
        if (!journal.enabled() || journal.pending().isEmpty()) {
            return;
        }
        try {
            journal.pending().forEach(erasure::replay);
            replayWarning = false;
        } catch (RuntimeException failure) {
            if (!replayWarning) {
                log.warn("event=assistant_privacy_replay_retry");
                replayWarning = true;
            }
        }
    }
}
