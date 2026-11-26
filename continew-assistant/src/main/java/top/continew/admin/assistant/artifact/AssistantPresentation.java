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
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a bounded text-only presentation and writes native PowerPoint title and bullet shapes.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
public final class AssistantPresentation {

    static final String FORMAT = "slides";

    /**
     * Media type for generated PPTX downloads.
     */
    public static final String MIME =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private static final int MAX_SLIDES = 12;
    private static final int MAX_BULLETS = 6;
    private static final int MAX_TITLE_UNITS = 60;
    private static final int MAX_BULLET_UNITS = 96;
    private static final int MAX_DOWNLOAD_BYTES = 1024 * 1024;
    private static final Color TITLE = new Color(20, 34, 60);
    private static final Color BODY = new Color(40, 52, 71);

    private AssistantPresentation() {
    }

    static Presentation input(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 1
            || !value.path("slides").isArray()) {
            throw invalid("Supply a presentation with an ordered list of slides.");
        }
        JsonNode slides = value.path("slides");
        if (slides.isEmpty() || slides.size() > MAX_SLIDES) {
            throw invalid("Choose between 1 and 12 slides.");
        }
        var pages = new ArrayList<Slide>(slides.size());
        for (JsonNode slide : slides) {
            if (!slide.isObject() || slide.size() != 2 || !slide.path("bullets").isArray()
                || slide.path("bullets").size() > MAX_BULLETS) {
                throw invalid(
                    "Each slide requires a title and a list of no more than six bullets.");
            }
            String title = text(slide.path("title"), MAX_TITLE_UNITS);
            var body = new ArrayList<String>(slide.path("bullets").size());
            for (JsonNode bullet : slide.path("bullets")) {
                body.add(text(bullet, MAX_BULLET_UNITS));
            }
            pages.add(new Slide(title, List.copyOf(body)));
        }
        return new Presentation(List.copyOf(pages));
    }

    static String encode(ObjectMapper mapper, Presentation deck) {
        try {
            return mapper.writeValueAsString(deck);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    static Presentation readStored(ObjectMapper mapper, String content) {
        try {
            return input(AssistantArtifacts.jsonReader(mapper).readTree(content));
        } catch (JsonProcessingException | AssistantApiException failure) {
            throw unavailable();
        }
    }

    static byte[] pptx(Presentation deck) {
        try (var document = new XMLSlideShow(); var output = new ByteArrayOutputStream()) {
            document.setPageSize(new Dimension(1280, 720));
            for (Slide unit : deck.slides()) {
                writeSlide(document.createSlide(), unit);
            }
            document.write(output);
            if (output.size() > MAX_DOWNLOAD_BYTES) {
                throw unavailable();
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    private static void writeSlide(XSLFSlide target, Slide unit) {
        XSLFTextBox title = textBox(target, 64, 48, 1152, 124);
        XSLFTextRun heading = title.addNewTextParagraph().addNewTextRun();
        style(heading, unit.title(), 34, TITLE);
        heading.setBold(true);
        if (unit.bullets().isEmpty()) {
            return;
        }
        XSLFTextBox list = textBox(target, 72, 186, 1120, 470);
        for (String bullet : unit.bullets()) {
            var paragraph = list.addNewTextParagraph();
            paragraph.setBullet(true);
            paragraph.setBulletCharacter("•");
            paragraph.setLeftMargin(32.0);
            paragraph.setIndent(-19.0);
            paragraph.setLineSpacing(112.0);
            paragraph.setSpaceAfter(-6.0);
            style(paragraph.addNewTextRun(), bullet, 22, BODY);
        }
    }

    private static XSLFTextBox textBox(XSLFSlide target, int left, int top, int width, int height) {
        XSLFTextBox box = target.createTextBox();
        box.setAnchor(new Rectangle2D.Double(left, top, width, height));
        box.setWordWrap(true);
        box.clearText();
        return box;
    }

    private static void style(XSLFTextRun run, String content, int size, Color color) {
        run.setText(content);
        run.setFontFamily("Arial");
        run.setFontSize((double) size);
        run.setFontColor(color);
    }

    private static String text(JsonNode value, int maxUnits) {
        if (!value.isTextual()) {
            throw invalid("Use one literal text line for each slide heading and bullet.");
        }
        String text = value.textValue();
        if (text.isBlank() || text.length() > maxUnits
            || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
            || text.codePoints().anyMatch(point -> Character.isISOControl(point) || point == 0xfffe
                || point == 0xffff || point == 0x2028 || point == 0x2029)) {
            throw invalid(
                "A slide heading or bullet contains invalid text or exceeds its character limit.");
        }
        return text;
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The presentation is temporarily unavailable.");
    }

    /**
     * An ordered collection of validated text slides.
     *
     * @param slides immutable authored slides
     */
    public record Presentation(List<Slide> slides) {

        @Override
        public String toString() {
            return "Presentation[protected]";
        }
    }

    /**
     * An authored heading and optional bullet list.
     *
     * @param title literal heading
     * @param bullets literal text lines in display order
     */
    public record Slide(String title, List<String> bullets) {

        @Override
        public String toString() {
            return "Slide[protected]";
        }
    }
}
