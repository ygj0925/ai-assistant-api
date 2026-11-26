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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Kind;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests model opt-in, configuration, and deterministic output.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantModelsTest {

    @Test
    void startsWithAnEmptyCatalog() {
        AssistantModels models = new AssistantModels(new MockEnvironment(), new ObjectMapper());
        try {
            assertThat(models.list()).isEmpty();
            assertThatThrownBy(() -> models.require(null))
                .isInstanceOfSatisfying(AssistantApiException.class,
                    exception -> assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
        } finally {
            models.close();
        }
    }

    @Test
    void restrictsTheMockToLocalProfiles() {
        MockEnvironment environment =
            new MockEnvironment().withProperty("assistant.models.mock-enabled", "true");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AssistantModels(environment, new ObjectMapper()));

        environment.setActiveProfiles("dev", "prod");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AssistantModels(environment, new ObjectMapper()));
    }

    @Test
    void producesBoundedUnicodeSafeMockFragments() {
        MockEnvironment environment =
            new MockEnvironment().withProperty("assistant.models.mock-enabled", "true");
        environment.setActiveProfiles("test");
        AssistantModels models = new AssistantModels(environment, new ObjectMapper());
        try {
            assertThat(models.list())
                .containsExactly(new AssistantModels.Model("local-echo", "synthetic-v1"));
            assertThat(models.require(null)).isEqualTo(models.require("local-echo"));
            List<String> chunks = new ArrayList<>();
            String prompt = "🦀中文".repeat(3000);
            models.generate("local-echo", List.of(new AssistantModels.Turn("user", prompt)),
                chunks::add);
            String reply = String.join("", chunks);
            String expected = "本地模拟回复：" + prompt;
            assertThat(reply)
                .isEqualTo(expected.substring(0, expected.offsetByCodePoints(0, 2048)));
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.codePointCount(0, chunk.length())).isBetween(1, 8);
                assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
                assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
            });
            assertThatThrownBy(() -> models.generate("local-echo",
                List.of(new AssistantModels.Turn("user", "中".repeat(65537))), chunks::add))
                .isInstanceOfSatisfying(AssistantApiException.class,
                    exception -> assertThat(exception.code()).isEqualTo("VALIDATION_ERROR"));
        } finally {
            models.close();
        }
    }

    @Test
    void acknowledgesSelectedHistoryWithoutEchoingItsContents() {
        MockEnvironment environment =
            new MockEnvironment().withProperty("assistant.models.mock-enabled", "true");
        environment.setActiveProfiles("test");
        AssistantModels models = new AssistantModels(environment, new ObjectMapper());
        try {
            var selected = new Material(Kind.HISTORY_REPLY, "私有来源🦀");
            var messages = List.of(new AssistantModels.Turn("user", "当前问题"));
            StringBuilder reply = new StringBuilder();
            models.generateWithLocalMemory(models.require("local-echo"), messages,
                List.of(selected), reply::append);
            assertThat(reply.toString()).isEqualTo("本地模拟回复（已使用 1 条所选历史回复）：当前问题")
                .doesNotContain(selected.content());
        } finally {
            models.close();
        }
    }

    @Test
    void requiresTheBeijingRegionAndAKeyForRemoteRoutes() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("assistant.models.ark.enabled", "true")
            .withProperty("assistant.models.ark.id", "fixture-model")
            .withProperty("assistant.models.ark.version", "fixture-version");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AssistantModels(environment, new ObjectMapper()))
            .withMessageContaining("assistant.models.region");
        environment.setProperty("assistant.models.region", "cn-beijing");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AssistantModels(environment, new ObjectMapper()))
            .withMessageContaining("ARK_API_KEY");
        environment.setProperty("ARK_API_KEY", "synthetic-test-key");
        AssistantModels models = new AssistantModels(environment, new ObjectMapper());
        try {
            assertThat(models.list())
                .containsExactly(new AssistantModels.Model("ark", "fixture-version"));
        } finally {
            models.close();
        }
    }

    @Test
    void resolvesOnlyConfiguredRetiredIdsForNewRequests() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("assistant.models.mock-enabled", "true")
            .withProperty("assistant.models.retired.old-chat", "local-echo");
        environment.setActiveProfiles("test");
        AssistantModels models = new AssistantModels(environment, new ObjectMapper());
        try {
            var model = new AssistantModels.Model("local-echo", "synthetic-v1", 32_768, "standard");
            assertThat(models.list()).containsExactly(model);
            assertThat(models.resolve(null)).isEqualTo(new AssistantModels.Selection(model, null));
            assertThat(models.resolve("local-echo"))
                .isEqualTo(new AssistantModels.Selection(model, null));
            assertThat(models.resolve("old-chat"))
                .isEqualTo(new AssistantModels.Selection(model, "old-chat"));
            assertThatThrownBy(() -> models.require("old-chat"))
                .isInstanceOfSatisfying(AssistantApiException.class,
                    exception -> assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
            assertThatThrownBy(() -> models.resolve("unknown-model"))
                .isInstanceOfSatisfying(AssistantApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(exception.getMessage()).contains("model catalog")
                        .doesNotContain("unknown-model");
                });
        } finally {
            models.close();
        }
    }

    @Test
    void rejectsAliasChainsAndActiveIdCollisions() {
        MockEnvironment chain = new MockEnvironment()
            .withProperty("assistant.models.mock-enabled", "true")
            .withProperty("assistant.models.retired.old-chat", "older-chat")
            .withProperty("assistant.models.retired.older-chat", "local-echo");
        chain.setActiveProfiles("test");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AssistantModels(chain, new ObjectMapper()))
            .withMessageContaining("directly to an enabled model");

        MockEnvironment collision = new MockEnvironment()
            .withProperty("assistant.models.mock-enabled", "true")
            .withProperty("assistant.models.retired.local-echo", "local-echo");
        collision.setActiveProfiles("test");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AssistantModels(collision, new ObjectMapper()))
            .withMessageContaining("Retired model IDs must be distinct");

        MockEnvironment disabled = new MockEnvironment()
            .withProperty("assistant.models.retired.old-chat", "local-echo");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AssistantModels(disabled, new ObjectMapper()))
            .withMessageContaining("directly to an enabled model");
    }

    @Test
    void enforcesTheTotalContextBudgetInCodePointsAcrossTurns() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("assistant.models.mock-enabled", "true");
        environment.setActiveProfiles("test");
        AssistantModels models = new AssistantModels(environment, new ObjectMapper());
        try {
            int budget = models.require(null).contextBudget();
            var history = new AssistantModels.Turn("assistant", "🦀".repeat(budget - 1));
            var chunks = new ArrayList<String>();
            models.generate("local-echo", List.of(history, new AssistantModels.Turn("user", "中")),
                chunks::add);
            assertThat(String.join("", chunks)).isEqualTo("本地模拟回复：中");
            assertThatThrownBy(() -> models.generate("local-echo",
                List.of(history, new AssistantModels.Turn("user", "中文")), chunks::add))
                .isInstanceOfSatisfying(AssistantApiException.class,
                    exception -> assertThat(exception.code()).isEqualTo("VALIDATION_ERROR"));
        } finally {
            models.close();
        }
    }
}
