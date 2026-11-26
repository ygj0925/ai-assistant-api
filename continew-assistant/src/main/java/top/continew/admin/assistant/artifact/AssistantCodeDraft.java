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

package top.continew.admin.assistant.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;

import java.util.Map;

/**
 * Validates a private literal source draft and selects a fixed text filename suffix.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
public final class AssistantCodeDraft {

    static final String FORMAT = "code";

    private static final Map<String, String> FILE_SUFFIXES = Map.of(
        "plaintext", ".txt",
        "javascript", ".js.txt",
        "typescript", ".ts.txt",
        "python", ".py.txt",
        "java", ".java.txt",
        "sql", ".sql.txt",
        "json", ".json.txt",
        "css", ".css.txt");

    private AssistantCodeDraft() {
    }

    static Code input(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 2
            || !value.path("language").isTextual() || !value.path("source").isTextual()) {
            throw invalid();
        }
        String language = value.path("language").textValue();
        if (!FILE_SUFFIXES.containsKey(language)) {
            throw invalid();
        }
        String source = value.path("source").textValue();
        AssistantArtifacts.size(source);
        return new Code(language, source);
    }

    static String encode(ObjectMapper mapper, Code code) {
        try {
            return mapper.writeValueAsString(code);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    static Code readStored(ObjectMapper mapper, String content) {
        try {
            return input(AssistantArtifacts.jsonReader(mapper).readTree(content));
        } catch (JsonProcessingException | AssistantApiException failure) {
            throw unavailable();
        }
    }

    static String fileSuffix(Code code) {
        return FILE_SUFFIXES.get(code.language());
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Supply literal source text and one supported code language.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The code draft is temporarily unavailable.");
    }

    /**
     * An explicitly labelled source file stored and served as literal text.
     *
     * @param language one supported source language
     * @param source the exact UTF-8 source text
     */
    public record Code(String language, String source) {

        @Override
        public String toString() {
            return "Code[protected]";
        }
    }
}
