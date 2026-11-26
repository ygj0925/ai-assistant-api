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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Kind;
import top.continew.admin.assistant.memory.AssistantGenerationMemory.Material;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Server-side catalog and routing for assistant text generation.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantModels {

    /**
     * Shared limit for message content in one model request, in Unicode code points.
     */
    public static final int MAX_CONTEXT_CODE_POINTS = 32_768;

    static final int MAX_OUTPUT_CODE_POINTS = 2048;
    static final Duration GENERATION_TIMEOUT = Duration.ofSeconds(45);

    private static final String NAMESPACE = "assistant.models.";
    private static final String BEIJING = "cn-beijing";
    private static final Model MOCK = new Model("local-echo", "synthetic-v1");
    private static final Pattern PUBLIC_MODEL_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private static final Pattern WORKSPACE_ID =
        Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final int MOCK_CHUNK_SIZE = 8;

    private final List<Provider> providers;
    private final Map<String, String> retiredIds;
    private final String defaultId;
    private final ScheduledThreadPoolExecutor deadlines;

    /**
     * Creates a catalog from explicitly enabled model settings.
     *
     * @param environment deployment and profile settings
     * @param mapper application JSON mapper
     */
    public AssistantModels(Environment environment, ObjectMapper mapper) {
        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "assistant-model-deadline");
            thread.setDaemon(true);
            return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
        List<Provider> configured = new ArrayList<>();
        if (enabled(environment, NAMESPACE + "mock-enabled")) {
            configureMock(environment, configured);
        }
        if (enabled(environment, NAMESPACE + "dashscope.enabled")) {
            String workspaceId = environment.getProperty(NAMESPACE + "dashscope.workspace-id", "");
            if (!workspaceId.isEmpty() && !WORKSPACE_ID.matcher(workspaceId).matches()) {
                throw new IllegalStateException("Invalid Beijing DashScope workspace ID.");
            }
            String host = workspaceId.isEmpty() ? "dashscope.aliyuncs.com"
                : workspaceId + ".cn-beijing.maas.aliyuncs.com";
            URI endpoint = URI.create("https://" + host + "/compatible-mode/v1/chat/completions");
            configured.add(remote(environment, mapper, "dashscope", "DASHSCOPE_API_KEY", endpoint));
        }
        if (enabled(environment, NAMESPACE + "ark.enabled")) {
            URI endpoint = URI.create("https://ark.cn-beijing.volces.com/api/v3/chat/completions");
            configured.add(remote(environment, mapper, "ark", "ARK_API_KEY", endpoint));
        }
        providers = List.copyOf(configured);
        defaultId = environment.getProperty(NAMESPACE + "default",
            configured.isEmpty() ? "" : configured.get(0).model().id());
        if (!defaultId.isEmpty()
            && providers.stream().noneMatch(provider -> provider.model().id().equals(defaultId))) {
            throw new IllegalStateException(
                "The assistant model default must name an enabled model.");
        }
        retiredIds = retirementMappings(environment);
    }

    /**
     * Lists enabled model metadata.
     *
     * @return enabled model metadata in selection order
     */
    public List<Model> list() {
        return providers.stream().map(Provider::model).toList();
    }

    /**
     * Selects an enabled model directly, using the deployment default for a missing ID.
     *
     * @param optionalId requested public ID or null
     * @return metadata to persist before starting a run
     */
    public Model require(String optionalId) {
        return select(optionalId).model();
    }

    /**
     * Resolves a new request using the explicitly configured retirement map.
     *
     * @param requested public model ID or null for the deployment default
     * @return enabled route and any retired model it replaces
     */
    public Selection resolve(String requested) {
        String replacement = requested == null ? null : retiredIds.get(requested);
        return replacement == null ? new Selection(require(requested), null)
            : new Selection(require(replacement), requested);
    }

    /**
     * Generates text and emits bounded fragments in provider order.
     *
     * @param modelId public catalog ID
     * @param messages oldest-to-newest conversation turns
     * @param onDelta destination for generated fragments
     */
    public void generate(String modelId, List<Turn> messages, Consumer<String> onDelta) {
        Provider provider = select(modelId);
        remainingContext(provider.model(), messages);
        provider.generator().generate(messages, List.of(), onDelta);
    }

    /**
     * Checks whether the recorded model can be given private material for a local test run.
     *
     * @param model resolved catalog entry
     * @return whether it exactly matches the local synthetic model
     */
    public boolean supportsLocalMemory(Model model) {
        return MOCK.equals(model);
    }

    /**
     * Provides privately selected material directly to the in-process synthetic model.
     *
     * @param model resolved and version-checked model
     * @param messages regular conversation turns
     * @param material bounded private sources
     * @param onDelta destination for generated fragments
     */
    public void generateWithLocalMemory(Model model, List<Turn> messages, List<Material> material,
        Consumer<String> onDelta) {
        Provider provider = select(model.id());
        if (!supportsLocalMemory(model) || !supportsLocalMemory(provider.model())
            || material.isEmpty() || material.size() > 3
            || messages.size() + material.size() > 32) {
            throw invalidContext();
        }
        int available = Math.min(2_000, remainingContext(provider.model(), messages));
        for (Material source : material) {
            if (source.kind() == null || source.content() == null || source.content().isBlank()) {
                throw invalidContext();
            }
            available -= units(source.content());
        }
        if (available < 0) {
            throw invalidContext();
        }
        provider.generator().generate(messages, material, onDelta);
    }

    private int remainingContext(Model model, List<Turn> messages) {
        if (messages.isEmpty() || messages.size() > 128) {
            throw invalidContext();
        }
        int remaining = model.contextBudget();
        for (Turn turn : messages) {
            if (turn == null || turn.content() == null
                || (!"user".equals(turn.role()) && !"assistant".equals(turn.role()))) {
                throw invalidContext();
            }
            remaining -= units(turn.content());
            if (remaining < 0) {
                throw invalidContext();
            }
        }
        if (!"user".equals(messages.get(messages.size() - 1).role())) {
            throw invalidContext();
        }
        return remaining;
    }

    private static int units(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * Stops outstanding deadline timers on application shutdown.
     */
    @PreDestroy
    public void close() {
        deadlines.shutdownNow();
    }

    private Provider select(String modelId) {
        String requested = modelId == null ? defaultId : modelId;
        return providers.stream().filter(provider -> provider.model().id().equals(requested))
            .findFirst()
            .orElseThrow(() -> new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PROVIDER_UNAVAILABLE",
                "The requested model is unavailable. See the model catalog for enabled options."));
    }

    private Map<String, String> retirementMappings(Environment environment) {
        Map<String, String> configured = Binder.get(environment)
            .bind(NAMESPACE + "retired", Bindable.mapOf(String.class, String.class))
            .orElse(Map.of());
        List<String> active = list().stream().map(Model::id).toList();
        for (var mapping : configured.entrySet()) {
            if (!PUBLIC_MODEL_ID.matcher(mapping.getKey()).matches()
                || active.contains(mapping.getKey()) || !active.contains(mapping.getValue())) {
                throw new IllegalStateException(
                    "Retired model IDs must be distinct and map directly to an enabled model.");
            }
        }
        return Map.copyOf(configured);
    }

    private Provider remote(Environment environment, ObjectMapper mapper, String name,
        String keyVariable, URI endpoint) {
        if (!BEIJING.equals(environment.getProperty(NAMESPACE + "region"))) {
            throw new IllegalStateException(
                "Beijing model routes require assistant.models.region=cn-beijing.");
        }
        String base = NAMESPACE + name + ".";
        String upstreamId = required(environment, base + "id");
        String version = required(environment, base + "version");
        String key = required(environment, keyVariable);
        if (key.chars().anyMatch(value -> value <= 32 || value >= 127)) {
            throw new IllegalStateException("Invalid model API key format for " + name + ".");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var generator = new OpenAiTextProvider(http, mapper, deadlines, endpoint, upstreamId, key);
        return new Provider(new Model(name, version), (messages, material, onDelta) -> {
            if (!material.isEmpty()) {
                throw invalidContext();
            }
            generator.generate(messages, onDelta);
        });
    }

    private static void configureMock(Environment environment, List<Provider> configured) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("prod")
            || (!profiles.contains("dev") && !profiles.contains("test"))) {
            throw new IllegalStateException(
                "The synthetic model requires the dev or test profile.");
        }
        int delay = environment.getProperty(NAMESPACE + "mock-delay-millis", Integer.class, 0);
        if (delay < 0 || delay > 1000) {
            throw new IllegalStateException(
                "assistant.models.mock-delay-millis must be between 0 and 1000.");
        }
        configured.add(new Provider(MOCK,
            (messages, material, onDelta) -> echo(messages, material, onDelta, delay)));
    }

    private static void echo(List<Turn> messages, List<Material> material, Consumer<String> onDelta,
        int delay) {
        String reply = syntheticPrefix(material) + messages.get(messages.size() - 1).content();
        int remaining = Math.min(MAX_OUTPUT_CODE_POINTS, reply.codePointCount(0, reply.length()));
        long deadline = System.nanoTime() + GENERATION_TIMEOUT.toNanos();
        int offset = 0;
        while (remaining > 0) {
            pause(delay);
            if (System.nanoTime() - deadline >= 0) {
                throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE",
                    "Model generation timed out.");
            }
            int size = Math.min(remaining, MOCK_CHUNK_SIZE);
            int end = reply.offsetByCodePoints(offset, size);
            onDelta.accept(reply.substring(offset, end));
            remaining -= size;
            offset = end;
        }
    }

    private static String syntheticPrefix(List<Material> material) {
        if (material.isEmpty()) {
            return "本地模拟回复：";
        }
        if (material.stream().anyMatch(source -> source.kind() == Kind.HISTORY_REPLY)) {
            return "本地模拟回复（已使用 1 条所选历史回复）：";
        }
        var custom = material.stream().filter(source -> source.kind() == Kind.CUSTOM_INSTRUCTIONS)
            .findFirst();
        long knowledge = material.stream().filter(source -> source.kind() == Kind.CUSTOM_KNOWLEDGE)
            .count();
        if (custom.isPresent() || knowledge > 0) {
            boolean bullets = custom.filter(source -> "格式：项目符号".equals(source.content()))
                .isPresent();
            return "本地定制助手（项目资料命中 " + knowledge + " 项）：" + (bullets ? "\n- " : "");
        }
        long instructions = material.stream().filter(source -> source.kind() == Kind.INSTRUCTIONS)
            .count();
        long personal = material.stream().filter(source -> source.kind() == Kind.PERSONAL_MEMORY)
            .count();
        long project = material.stream().filter(source -> source.kind() == Kind.PROJECT_MEMORY)
            .count();
        boolean bullets = material.stream().anyMatch(source -> source.kind() == Kind.INSTRUCTIONS
            && "格式：项目符号".equals(source.content()));
        return "本地模拟回复（自定义指令 " + instructions + "，个人记忆 " + personal
            + "，项目记忆 " + project + "）：" + (bullets ? "\n- " : "");
    }

    private static void pause(int delay) {
        if (delay == 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                "Model generation stopped.");
        }
    }

    private static boolean enabled(Environment environment, String key) {
        return Boolean.TRUE.equals(environment.getProperty(key, Boolean.class, false));
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing settings for enabled assistant model: " + key);
        }
        return value;
    }

    private static AssistantApiException invalidContext() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The text model requires bounded user and assistant turns ending with a user message.");
    }

    /**
     * Public model metadata.
     *
     * @param id application model ID
     * @param version deployment version
     * @param contextBudget total Unicode code points available for turn content
     * @param tier available inference tier
     */
    public record Model(String id, String version, int contextBudget, String tier) {

        /**
         * Creates the metadata for a standard text model.
         *
         * @param id application model ID
         * @param version deployment version
         */
        public Model(String id, String version) {
            this(id, version, MAX_CONTEXT_CODE_POINTS, "standard");
        }
    }

    /**
     * Effective model selection and the requested retired ID, when it changed.
     *
     * @param model enabled model metadata
     * @param fallbackFromModelId retired model ID or null
     */
    public record Selection(Model model, String fallbackFromModelId) {
    }

    /**
     * One message in a generation context.
     *
     * @param role message author role
     * @param content text of the message
     */
    public record Turn(String role, String content) {
    }

    private record Provider(Model model, Generator generator) {
    }

    @FunctionalInterface
    private interface Generator {

        void generate(List<Turn> messages, List<Material> material, Consumer<String> onDelta);
    }
}
