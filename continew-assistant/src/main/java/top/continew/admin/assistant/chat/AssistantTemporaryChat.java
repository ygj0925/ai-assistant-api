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

package top.continew.admin.assistant.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.model.AssistantModels;
import top.continew.admin.assistant.model.AssistantModels.Turn;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Short-lived replies using the synthetic model with no persistence or workspace context.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantTemporaryChat {

    /**
     * The bounded synthetic model used in temporary chat.
     */
    public static final String MODEL_ID = "local-echo";
    private static final int MAX_BODY_BYTES = 8192;
    private static final int MAX_PROMPT_CODE_POINTS = 512;

    private final AssistantModels models;
    private final ObjectReader input;
    private final boolean enabled;
    private final Semaphore capacity = new Semaphore(4);

    /**
     * Connects the local-only gateway and strict, bounded input reader.
     *
     * @param models text gateway
     * @param mapper application JSON mapper
     * @param enabled explicit temporary chat flag
     */
    public AssistantTemporaryChat(AssistantModels models, ObjectMapper mapper,
        @Value("${assistant.temporary-chat.enabled:false}") boolean enabled) {
        this.models = models;
        this.enabled = enabled;
        this.input = mapper.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /**
     * Reports whether the explicit local synthetic route is ready.
     *
     * @return whether the entry point is available
     */
    public boolean available() {
        return enabled && models.list().stream().anyMatch(model -> MODEL_ID.equals(model.id()));
    }

    /**
     * Generates one reply without reading or persisting account content.
     *
     * @param request bounded raw JSON request
     * @return short-lived response
     */
    public Reply reply(HttpServletRequest request) {
        if (!available()) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "Temporary chat is unavailable.");
        }
        if (!capacity.tryAcquire()) {
            throw new AssistantApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Try again later.");
        }
        try {
            String content = prompt(request);
            StringBuilder reply = new StringBuilder();
            models.generate(MODEL_ID, List.of(new Turn("user", content)), reply::append);
            return new Reply(reply.toString(), MODEL_ID);
        } finally {
            capacity.release();
        }
    }

    private String prompt(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw invalid();
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw invalid();
            }
            JsonNode body = input.readValue(bytes);
            if (body == null || !body.isObject() || body.size() != 1
                || !body.path("content").isTextual()) {
                throw invalid();
            }
            String text = body.get("content").textValue();
            if (text.isBlank() || text.codePointCount(0, text.length()) > MAX_PROMPT_CODE_POINTS
                || text.codePoints().anyMatch(c -> c >= Character.MIN_SURROGATE
                    && c <= Character.MAX_SURROGATE)) {
                throw invalid();
            }
            return text;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Temporary chat requires one content field with 1 to 512 characters.");
    }

    /**
     * One ephemeral synthetic reply.
     *
     * @param content generated text
     * @param modelId actual local model
     */
    public record Reply(String content, String modelId) {
    }
}
