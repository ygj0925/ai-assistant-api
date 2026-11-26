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

package top.continew.admin.assistant.research;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances persisted, approved research without any model or network access.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@ConditionalOnProperty(name = {"assistant.enabled", "assistant.research.enabled",
    "assistant.retrieval.enabled"}, havingValue = "true")
public class AssistantResearchWorker {

    private final AssistantResearch research;

    /**
     * Creates a worker for the approved offline catalogue.
     *
     * @param research durable research service
     */
    public AssistantResearchWorker(AssistantResearch research) {
        this.research = research;
    }

    /**
     * Advances one due checkpoint.
     */
    @Scheduled(fixedDelay = 250)
    public void tick() {
        research.advanceOne();
    }
}
