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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded OpenAI-compatible text transport for configured providers.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class OpenAiTextProvider {

    private static final int MAX_WIRE_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_TOKENS = 768;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService deadlines;
    private final URI endpoint;
    private final String modelId;
    private final String key;

    OpenAiTextProvider(HttpClient http, ObjectMapper mapper, ScheduledExecutorService deadlines,
        URI endpoint, String modelId, String key) {
        this.http = http;
        this.mapper = mapper;
        this.deadlines = deadlines;
        this.endpoint = endpoint;
        this.modelId = modelId;
        this.key = key;
    }

    void generate(List<AssistantModels.Turn> messages, Consumer<String> onDelta) {
        long expiry = System.nanoTime() + AssistantModels.GENERATION_TIMEOUT.toNanos();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of("model", modelId, "messages", messages,
                "stream", true, "max_tokens", MAX_OUTPUT_TOKENS));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(AssistantModels.GENERATION_TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
            HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                checkResponse(response);
                long remaining = expiry - System.nanoTime();
                if (remaining <= 0) {
                    throw unavailable("Model generation timed out.");
                }
                var deadline = deadlines.schedule(() -> {
                    timedOut.set(true);
                    closeAtDeadline(input);
                }, remaining, TimeUnit.NANOSECONDS);
                try {
                    readStream(mapper, input, onDelta);
                    if (timedOut.get()) {
                        throw unavailable("Model generation timed out.");
                    }
                } finally {
                    deadline.cancel(false);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("Model generation stopped.");
        } catch (IOException exception) {
            if (timedOut.get() || System.nanoTime() - expiry >= 0) {
                throw unavailable("Model generation timed out.");
            }
            throw unavailable("The model stream could not be read.");
        }
    }

    private static void checkResponse(HttpResponse<InputStream> response) {
        if (response.statusCode() == 429) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "The model is busy. Try again later.");
        }
        if (response.statusCode() != 200) {
            throw unavailable("The model provider is unavailable.");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("")
            .toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!"text/event-stream".equals(contentType)) {
            throw unavailable("The model provider returned an invalid stream.");
        }
    }

    private static void closeAtDeadline(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The worker will report a sanitized failure.
        }
    }

    static void readStream(ObjectMapper mapper, InputStream input, Consumer<String> onDelta)
        throws IOException {
        BufferedReader lines = new BufferedReader(new InputStreamReader(new BoundedInput(input),
            StandardCharsets.UTF_8));
        StringBuilder part = new StringBuilder();
        int remaining = AssistantModels.MAX_OUTPUT_CODE_POINTS;
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.startsWith("data:")) {
                if (!part.isEmpty()) {
                    part.append('\n');
                }
                part.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
            } else if (line.isEmpty() && !part.isEmpty()) {
                String event = part.toString();
                part.setLength(0);
                if ("[DONE]".equals(event.trim())) {
                    if (remaining == AssistantModels.MAX_OUTPUT_CODE_POINTS) {
                        throw unavailable("The model produced no text.");
                    }
                    return;
                }
                String text = textFromEvent(mapper.readTree(event));
                if (!text.isEmpty()) {
                    int size = Math.min(remaining, text.codePointCount(0, text.length()));
                    onDelta.accept(text.substring(0, text.offsetByCodePoints(0, size)));
                    remaining -= size;
                    if (remaining == 0) {
                        return;
                    }
                }
            }
        }
        throw unavailable("The model stream ended before completion.");
    }

    private static String textFromEvent(JsonNode event) {
        if (event == null || !event.isObject() || event.hasNonNull("error")) {
            throw unavailable("The model provider reported an error.");
        }
        JsonNode choice = event.path("choices").path(0);
        if ("content_filter".equals(choice.path("finish_reason").asText())) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "The model did not return a text response.");
        }
        JsonNode text = choice.path("delta").path("content");
        return text.isTextual() ? text.asText() : "";
    }

    private static AssistantApiException unavailable(String message) {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
            message);
    }

    private static final class BoundedInput extends FilterInputStream {

        private int remaining = MAX_WIRE_BYTES;

        private BoundedInput(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            ensureAvailable();
            int result = in.read();
            if (result >= 0) {
                remaining--;
            }
            return result;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            ensureAvailable();
            int result = in.read(bytes, offset, Math.min(length, remaining));
            if (result > 0) {
                remaining -= result;
            }
            return result;
        }

        private void ensureAvailable() throws IOException {
            if (remaining == 0) {
                throw new IOException("Provider response exceeded its wire limit.");
            }
        }
    }
}
