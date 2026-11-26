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
import org.springframework.web.util.HtmlUtils;
import top.continew.admin.assistant.account.AssistantApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Renders owned structured text into a fixed, standalone document with no active elements.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
public final class AssistantWebpage {

    static final String FORMAT = "webpage";

    private static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    private static final int MAX_SECTIONS = 6;
    private static final int MAX_PARAGRAPHS = 4;
    private static final int MAX_PARAGRAPH_UNITS = 400;
    private static final int MAX_HEADING_UNITS = 80;
    private static final String STYLE =
        """
            :root { color-scheme: light; font-family: system-ui, sans-serif; }
            body { margin: 0; color: #202731; background: #f7f8fa; }
            main { box-sizing: border-box; width: min(100%, 54rem); margin: 0 auto; padding: clamp(1.25rem, 5vw, 3.5rem); }
            header { border-bottom: 1px solid #d6dbe2; padding-bottom: 1.5rem; }
            section { margin-top: 2rem; }
            h1, h2 { line-height: 1.18; margin: 0 0 0.75rem; overflow-wrap: anywhere; }
            h1 { font-size: clamp(2rem, 6vw, 3.5rem); }
            h2 { font-size: 1.35rem; }
            p { margin: 0 0 0.75rem; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
            """;
    private static final String DOCUMENT_POLICY =
        "default-src 'none'; style-src 'sha256-" + styleHash()
            + "'; base-uri 'none'; form-action 'none'";
    static final String RESPONSE_POLICY =
        "sandbox; " + DOCUMENT_POLICY + "; frame-ancestors 'none'";

    private AssistantWebpage() {
    }

    static Website input(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 3
            || !value.path("sections").isArray()
            || value.path("sections").isEmpty() || value.path("sections").size() > MAX_SECTIONS) {
            throw invalid("Supply a heading, introduction and one to six webpage sections.");
        }
        String heading = text(value.path("heading"), MAX_HEADING_UNITS, false, false);
        String introduction = text(value.path("introduction"), 320, true, true);
        var sections = new ArrayList<Section>(value.path("sections").size());
        for (JsonNode section : value.path("sections")) {
            JsonNode paragraphs = section.path("paragraphs");
            if (!section.isObject() || section.size() != 2 || !paragraphs.isArray()
                || paragraphs.isEmpty() || paragraphs.size() > MAX_PARAGRAPHS) {
                throw invalid("Each webpage section needs a title and one to four paragraphs.");
            }
            String title = text(section.path("title"), 60, false, false);
            var body = new ArrayList<String>(paragraphs.size());
            for (JsonNode paragraph : paragraphs) {
                body.add(text(paragraph, MAX_PARAGRAPH_UNITS, true, false));
            }
            sections.add(new Section(title, List.copyOf(body)));
        }
        return new Website(heading, introduction, List.copyOf(sections));
    }

    static Website fromReply(String title, String reply) {
        String source = reply.replace("\r\n", "\n").replace('\r', '\n').strip();
        int maximumParagraphs = MAX_SECTIONS * MAX_PARAGRAPHS;
        if (source.isBlank() || source.length() > maximumParagraphs * MAX_PARAGRAPH_UNITS) {
            throw invalid(
                "Choose a complete reply with up to 9600 UTF-16 units to generate a webpage.");
        }
        String heading = title.substring(0, boundedEnd(title, 0, MAX_HEADING_UNITS)).strip();
        var paragraphs = new ArrayList<String>();
        for (int start = 0; start < source.length();) {
            int end = boundedEnd(source, start, MAX_PARAGRAPH_UNITS);
            String paragraph = source.substring(start, end);
            if (!paragraph.isBlank()) {
                paragraphs.add(text(paragraph, MAX_PARAGRAPH_UNITS, true, false));
            }
            start = end;
        }
        if (paragraphs.size() > maximumParagraphs) {
            throw invalid(
                "Choose a shorter reply to generate a webpage without splitting text characters.");
        }
        var sections = new ArrayList<Section>();
        for (int start = 0; start < paragraphs.size(); start += MAX_PARAGRAPHS) {
            int count = Math.min(start + MAX_PARAGRAPHS, paragraphs.size());
            String sectionTitle = start == 0 ? "回复内容" : "回复内容 " + (sections.size() + 1);
            sections.add(new Section(sectionTitle, List.copyOf(paragraphs.subList(start, count))));
        }
        return new Website(text(heading, MAX_HEADING_UNITS, false, false), "",
            List.copyOf(sections));
    }

    private static int boundedEnd(String value, int start, int limit) {
        int end = Math.min(start + limit, value.length());
        return end < value.length() && Character.isHighSurrogate(value.charAt(end - 1)) ? end - 1
            : end;
    }

    static String encode(ObjectMapper mapper, Website website) {
        try {
            return mapper.writeValueAsString(website);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    static Website readStored(ObjectMapper mapper, String content) {
        try {
            return input(AssistantArtifacts.jsonReader(mapper).readTree(content));
        } catch (JsonProcessingException | AssistantApiException failure) {
            throw unavailable();
        }
    }

    static byte[] render(Website website) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
            .append(DOCUMENT_POLICY).append("\">");
        element(html, "title", website.heading());
        html.append("<style>").append(STYLE).append("</style></head><body><main><header>");
        element(html, "h1", website.heading());
        if (!website.introduction().isBlank()) {
            element(html, "p", website.introduction());
        }
        html.append("</header>");
        for (Section section : website.sections()) {
            html.append("<section>");
            element(html, "h2", section.title());
            for (String paragraph : section.paragraphs()) {
                element(html, "p", paragraph);
            }
            html.append("</section>");
        }
        html.append("</main></body></html>\n");
        byte[] result = html.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_DOCUMENT_BYTES) {
            throw unavailable();
        }
        return result;
    }

    private static void element(StringBuilder html, String tag, String text) {
        html.append('<').append(tag).append('>')
            .append(HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name()))
            .append("</").append(tag).append('>');
    }

    private static String text(JsonNode value, int limit, boolean multiline, boolean blankAllowed) {
        if (!value.isTextual()) {
            throw invalid("Webpage fields accept literal text.");
        }
        return text(value.textValue(), limit, multiline, blankAllowed);
    }

    private static String text(String text, int limit, boolean multiline, boolean blankAllowed) {
        if (text.length() > limit || !blankAllowed && text.isBlank()
            || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
            || text.codePoints().anyMatch(point -> point == 0xfffe || point == 0xffff
                || !multiline && (point == 0x2028 || point == 0x2029)
                || Character.isISOControl(point)
                    && !(multiline && (point == '\t' || point == '\r' || point == '\n')))) {
            throw invalid(
                "A webpage field contains invalid characters or exceeds its length limit.");
        }
        return text;
    }

    private static String styleHash() {
        try {
            byte[] hash =
                MessageDigest.getInstance("SHA-256").digest(STYLE.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The fixed webpage stylesheet cannot be verified.",
                failure);
        }
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The private webpage is temporarily unavailable.");
    }

    /**
     * A structured private webpage.
     *
     * @param heading main heading and document title
     * @param introduction optional introductory text represented by an empty string when unused
     * @param sections ordered authored sections
     */
    public record Website(String heading, String introduction, List<Section> sections) {

        @Override
        public String toString() {
            return "Website[protected]";
        }
    }

    /**
     * A webpage section with a heading and body paragraphs.
     *
     * @param title literal section heading
     * @param paragraphs ordered text paragraphs
     */
    public record Section(String title, List<String> paragraphs) {

        @Override
        public String toString() {
            return "Section[protected]";
        }
    }
}
