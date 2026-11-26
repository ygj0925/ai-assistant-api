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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.account.AssistantApiException;

import java.nio.charset.StandardCharsets;

/**
 * Verifies literal text, fixed names and independent byte bounds for private code drafts.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantCodeDraftTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesExactSourceWhileSelectingAnAllowlistedTextSuffix() {
        String source = "const note = \"合成草稿 🦀\";\r\n\t// <script>synthetic</script>\r\n ";
        var code = draft("javascript", source);
        String canonical = AssistantCodeDraft.encode(mapper, code);
        var reopened = AssistantCodeDraft.readStored(mapper, canonical);
        assertThat(reopened.language()).isEqualTo("javascript");
        assertThat(reopened.source().getBytes(StandardCharsets.UTF_8))
            .isEqualTo(source.getBytes(StandardCharsets.UTF_8));
        assertThat(AssistantCodeDraft.fileSuffix(reopened)).isEqualTo(".js.txt");
        assertThat(AssistantCodeDraft.fileSuffix(draft("plaintext", ""))).isEqualTo(".txt");
        assertThat(AssistantCodeDraft.fileSuffix(draft("python", ""))).isEqualTo(".py.txt");
    }

    @Test
    void rejectsInvalidLanguagesAndByteOverflowBeforeStoring() {
        expect(400, () -> draft("../malicious.js", "Synthetic"));
        expect(400, () -> draft("html", "<p>Synthetic</p>"));
        expect(400, () -> draft("java", "Invalid" + Character.MIN_VALUE + "control"));
        expect(413, () -> draft("plaintext", "a".repeat(32769)));
        var escaped = draft("json", "\\".repeat(16384));
        expect(413, () -> AssistantArtifacts.size(AssistantCodeDraft.encode(mapper, escaped)));
    }

    private AssistantCodeDraft.Code draft(String language, String source) {
        return AssistantCodeDraft
            .input(mapper.createObjectNode().put("language", language).put("source", source));
    }

    private static void expect(int code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AssistantApiException.class,
            failure -> assertThat(failure.status().value()).isEqualTo(code));
    }
}
