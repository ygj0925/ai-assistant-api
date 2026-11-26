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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.account.AssistantApiException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the SSE decoder using synthetic provider payloads.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class OpenAiTextProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void handlesMultilineSseAndEmitsOnlyResponseText() throws IOException {
        String fixture = """
            : heartbeat
            data: {"choices":[{"delta":
            data: {"reasoning_content":"private reasoning","content":"中文 🦀"}}]}

            data: {"choices":[{"delta":{"content":"完成"}}]}

            data: {"choices":[],"usage":{"total_tokens":13}}

            data: [DONE]

            """.replace("\n", "\r\n");
        List<String> chunks = new ArrayList<>();
        OpenAiTextProvider.readStream(mapper, bytes(fixture), chunks::add);
        assertThat(chunks).containsExactly("中文 🦀", "完成");
    }

    @Test
    void preservesPartialTextAndReportsATruncatedStream() {
        String fixture = "data: {\"choices\":[{\"delta\":{\"content\":\"部分输出\"}}]}\n\n";
        List<String> chunks = new ArrayList<>();
        assertThatThrownBy(() -> OpenAiTextProvider.readStream(mapper, bytes(fixture), chunks::add))
            .isInstanceOfSatisfying(AssistantApiException.class, exception -> {
                assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                assertThat(exception.getMessage()).contains("before completion");
            });
        assertThat(chunks).containsExactly("部分输出");
    }

    @Test
    void protectsProviderDetailsAndLimitsWireBytes() {
        List<String> chunks = new ArrayList<>();
        assertThatThrownBy(() -> OpenAiTextProvider.readStream(mapper,
            bytes("data: {\"error\":{\"message\":\"private provider detail\"}}\n\n"), chunks::add))
            .isInstanceOfSatisfying(AssistantApiException.class, exception -> {
                assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                assertThat(exception.getMessage()).doesNotContain("private provider detail");
            });
        assertThatThrownBy(
            () -> OpenAiTextProvider.readStream(mapper, bytes("x".repeat(1024 * 1024 + 1)),
                chunks::add))
            .isInstanceOf(IOException.class);
        assertThat(chunks).isEmpty();
    }

    @Test
    void keepsUnicodeIntactWhenTheOutputBudgetIsExhausted() throws IOException {
        String payload =
            "data: {\"choices\":[{\"delta\":{\"content\":\"" + "🦀".repeat(2050) + "\"}}]}\n\n";
        List<String> chunks = new ArrayList<>();
        OpenAiTextProvider.readStream(mapper, bytes(payload), chunks::add);
        assertThat(chunks).containsExactly("🦀".repeat(2048));
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
