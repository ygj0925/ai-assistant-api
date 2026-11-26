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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.account.AssistantApiException;

/**
 * Checks canonical draft encoding and exact approval source binding.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantArtifactImagesTest {

    private static final String APPROVED = "c65d10f1-6fad-4e74-87ea-ad4967be31a5";
    private static final String OTHER = "c65d10f1-6fad-4e74-87ea-ad4967be31a6";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsOnlyStoredReferenceToMatchingApproval() {
        var reference = AssistantArtifactImages.input(input(APPROVED, " 图像 🦀 "));
        var stored = AssistantArtifactImages.encode(mapper, reference);
        var image = AssistantArtifactImages.image(mapper, stored,
            new AssistantArtifactImages.Source(APPROVED, 79, 1, 2));
        assertEquals(new AssistantArtifactImages.Image(APPROVED, "图像 🦀", 79, 1, 2), image);
        var shared = mapper.valueToTree(image.shared());
        assertEquals(mapper.createObjectNode().put("altText", "图像 🦀").put("size", 79)
            .put("width", 1).put("height", 2), shared);
        assertThrows(AssistantApiException.class,
            () -> AssistantArtifactImages.image(mapper, stored,
                new AssistantArtifactImages.Source(OTHER, 79, 1, 2)));
        assertThrows(AssistantApiException.class,
            () -> AssistantArtifactImages.image(mapper, stored,
                new AssistantArtifactImages.Source(APPROVED, 79, 2048, 2048)));
    }

    @Test
    void rejectsMalformedOrUnboundedAlternativeText() {
        assertEquals("", AssistantArtifactImages.input(input(APPROVED, "")).altText());
        assertEquals(160,
            AssistantArtifactImages.input(input(APPROVED, "甲".repeat(160))).altText().length());
        assertThrows(AssistantApiException.class,
            () -> AssistantArtifactImages.input(input(APPROVED, "甲".repeat(161))));
        assertThrows(AssistantApiException.class,
            () -> AssistantArtifactImages.input(input(APPROVED, "图片\n标题")));
        assertThrows(AssistantApiException.class,
            () -> AssistantArtifactImages.input(input("attach", "图")));
        assertThrows(AssistantApiException.class,
            () -> AssistantArtifactImages.input(input(APPROVED, "图").put("size", 79)));
    }

    private ObjectNode input(String attachment, String alt) {
        return mapper.createObjectNode().put("attachmentId", attachment).put("altText", alt);
    }
}
