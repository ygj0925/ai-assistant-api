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
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.account.AssistantApiException;

import java.awt.Dimension;
import java.io.ByteArrayInputStream;

/**
 * Verifies bounded slides and actual Office package readback.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantPresentationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void producesAReadablePowerPointDeckWithNativeEditableBulletsAndLiteralText() throws Exception {
        var deck = presentation("""
            {"slides":[{"title":"合成发展计划 🦀","bullets":["本季度进展","https://example.invalid / <tag>"]},
              {"title":"下一步","bullets":[]}]}
            """);
        assertThat(
            AssistantPresentation.readStored(mapper, AssistantPresentation.encode(mapper, deck)))
            .isEqualTo(deck);
        byte[] file = AssistantPresentation.pptx(deck);
        assertThat(file).startsWith((byte) 'P', (byte) 'K');
        try (var reopened = new XMLSlideShow(new ByteArrayInputStream(file))) {
            assertThat(reopened.getPageSize()).isEqualTo(new Dimension(1280, 720));
            assertThat(reopened.getSlides()).hasSize(2);
            var opening = reopened.getSlides().get(0).getShapes();
            assertThat(opening).hasSize(2);
            var title = (XSLFTextBox) opening.get(0);
            var list = (XSLFTextBox) opening.get(1);
            assertThat(title.getText()).isEqualTo("合成发展计划 🦀");
            var heading = title.getTextParagraphs().get(0).getTextRuns().get(0);
            assertThat(heading.isBold()).isTrue();
            assertThat(heading.getFontSize()).isEqualTo(34.0);
            assertThat(title.getAnchor().getMaxY()).isLessThan(list.getAnchor().getMinY());
            assertThat(list.getAnchor().getMaxY()).isLessThan(720);
            assertThat(list.getTextParagraphs()).extracting(XSLFTextParagraph::getText)
                .containsExactly("本季度进展", "https://example.invalid / <tag>");
            for (var bullet : list.getTextParagraphs()) {
                assertThat(bullet.isBullet()).isTrue();
                assertThat(bullet.getTextRuns().get(0).getFontSize()).isEqualTo(22.0);
                assertThat(bullet.getTextRuns().get(0).getHyperlink()).isNull();
            }
            var last = reopened.getSlides().get(1).getShapes();
            assertThat(last).hasSize(1);
            assertThat(((XSLFTextBox) last.get(0)).getText()).isEqualTo("下一步");
        }
    }

    @Test
    void validatesCountsAndExactSingleLineTextLimits() throws Exception {
        String page =
            "{\"title\":\"" + "章".repeat(60) + "\",\"bullets\":[\"" + "字".repeat(96) + "\"]}";
        assertThat(presentation("{\"slides\":[" + (page + ",").repeat(11) + page + "]}").slides())
            .hasSize(12);
        expectBadRequest("{\"slides\":[" + (page + ",").repeat(12) + page + "]}");
        expectBadRequest("{\"slides\":[]}");
        expectBadRequest("{\"slides\":[{\"title\":\" \",\"bullets\":[]}]}");
        expectBadRequest("{\"slides\":[{\"title\":\"" + "章".repeat(61) + "\",\"bullets\":[]}]}");
        expectBadRequest(
            "{\"slides\":[{\"title\":\"Valid\",\"bullets\":[\"" + "字".repeat(97) + "\"]}]}");
        expectBadRequest(
            "{\"slides\":[{\"title\":\"Valid\",\"bullets\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}]}");
        expectBadRequest("{\"slides\":[{\"title\":\"Line\",\"bullets\":[\"a\\nb\"]}]}");
        expectBadRequest("{\"slides\":[{\"title\":\"Line\",\"bullets\":[\"a\\u2028b\"]}]}");
        expectBadRequest("{\"slides\":[{\"title\":\"Type\",\"bullets\":[{\"text\":\"URL\"}]}]}");
    }

    private AssistantPresentation.Presentation presentation(String json)
        throws JsonProcessingException {
        return AssistantPresentation.input(AssistantArtifacts.jsonReader(mapper).readTree(json));
    }

    private void expectBadRequest(String json) {
        assertThatThrownBy(() -> presentation(json)).isInstanceOfSatisfying(
            AssistantApiException.class,
            failure -> assertThat(failure.status().value()).isEqualTo(400));
    }
}
