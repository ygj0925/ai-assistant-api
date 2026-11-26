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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.account.AssistantApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Verifies text-only HTML assembly, source preservation and the stylesheet's precise CSP hash.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantWebpageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rendersOnlyFixedMarkupAndHashesTheExactEmbeddedStylesheet() throws Exception {
        var website = website(
            """
                {"heading":"合成 🦀 <script>text</script>","introduction":"保留换行\\r\\n第二行",
                  "sections":[{"title":"<form>字面形式</form>",
                    "paragraphs":["https://example.invalid/ <img src=\\"https://example.invalid/x\\"> & B"]}]}
                """);
        assertThat(AssistantWebpage.readStored(mapper, AssistantWebpage.encode(mapper, website)))
            .isEqualTo(website);
        String html = new String(AssistantWebpage.render(website), StandardCharsets.UTF_8);
        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("<h1>合成 🦀 &lt;script&gt;text&lt;/script&gt;</h1>",
            "<p>保留换行\r\n第二行</p>", "&lt;img src=&quot;https://example.invalid/x&quot;&gt; &amp; B");
        assertThat(html).doesNotContain("<script", "<img", "<form", "<iframe", " href=");
        var css = Pattern.compile("<style>(.*?)</style>", Pattern.DOTALL).matcher(html);
        assertThat(css.find()).isTrue();
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(css.group(1).getBytes(StandardCharsets.UTF_8));
        String styleSource = "'sha256-" + Base64.getEncoder().encodeToString(hash) + "'";
        assertThat(html).contains("style-src " + styleSource);
        assertThat(AssistantWebpage.RESPONSE_POLICY).contains("sandbox", "default-src 'none'",
            "style-src " + styleSource, "form-action 'none'", "frame-ancestors 'none'");
    }

    @Test
    void requiresKnownFieldsAndBoundedNonemptySectionsWhileAllowingBlankIntro() throws Exception {
        var permitted = website("""
            {"heading":"有标题","introduction":"","sections":[{"title":"子标题","paragraphs":["正文"]}]}
            """);
        assertThat(permitted.introduction()).isEmpty();
        String section = "{\"title\":\"section\",\"paragraphs\":[\"text\"]}";
        expectBadRequest("{\"heading\":\"title\",\"introduction\":\"\",\"sections\":[]}");
        expectBadRequest(
            "{\"heading\":\"title\",\"introduction\":\"\",\"url\":\"https://example.invalid\",\"sections\":["
                + section + "]}");
        expectBadRequest("{\"heading\":\"title\",\"introduction\":\"\",\"sections\":["
            + (section + ",").repeat(6)
            + section + "]}");
        expectBadRequest(
            "{\"heading\":\"title\\nline\",\"introduction\":\"\",\"sections\":[" + section + "]}");
        expectBadRequest(
            "{\"heading\":\"title\",\"introduction\":\"\",\"sections\":[{\"title\":\"section\",\"paragraphs\":[\""
                + "文".repeat(401) + "\"]}]}");
    }

    @Test
    void safelyDerivesAFullBoundedPageFromAReply() {
        String title = "标题".repeat(39) + "文🦀";
        String reply = "测".repeat(399) + "🦀\r\n第二行 <figure>字面标签</figure>";
        var website = AssistantWebpage.fromReply(title, reply);
        assertThat(website.heading()).isEqualTo(title.substring(0, 79));
        assertThat(String.join("", website.sections().get(0).paragraphs()))
            .isEqualTo(reply.replace("\r\n", "\n"));
        String html = new String(AssistantWebpage.render(website), StandardCharsets.UTF_8);
        assertThat(html).contains("&lt;figure&gt;字面标签&lt;/figure&gt;").doesNotContain("<figure>");
        assertThatThrownBy(() -> AssistantWebpage.fromReply("标题", "字".repeat(9601)))
            .isInstanceOfSatisfying(AssistantApiException.class,
                failure -> assertThat(failure.status().value()).isEqualTo(400));
    }

    private AssistantWebpage.Website website(String json) throws JsonProcessingException {
        return AssistantWebpage.input(AssistantArtifacts.jsonReader(mapper).readTree(json));
    }

    private void expectBadRequest(String json) {
        assertThatThrownBy(() -> website(json)).isInstanceOfSatisfying(AssistantApiException.class,
            failure -> assertThat(failure.status().value()).isEqualTo(400));
    }
}
