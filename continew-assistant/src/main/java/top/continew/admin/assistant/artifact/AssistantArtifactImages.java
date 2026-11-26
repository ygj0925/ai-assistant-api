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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof.Origin;
import top.continew.admin.assistant.attachment.AttachmentImageEditProof.Proof;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Validates explicit references to approved PNGs and projects only safe source metadata.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
public final class AssistantArtifactImages {

    /**
     * Private image artifact format.
     */
    public static final String FORMAT = "image";

    /**
     * Approved asset coordinates selected alongside an artifact version.
     */
    public static final String SOURCE_PROJECTION = """
        approved_image.id AS image_attachment_id, approved_image.size_bytes AS image_size,
        approved_image.width AS image_width, approved_image.height AS image_height,
        approved_image.media_kind AS image_media_kind,
        """ + AttachmentImageEditProof.PROJECTION;

    /**
     * Attaches the current approval for version alias v while retaining unavailable heads.
     */
    public static final String SOURCE_JOIN =
        """
            LEFT JOIN assistant_artifact_image_source image_ref
                ON image_ref.artifact_id = v.artifact_id AND image_ref.version_id = v.id
            LEFT JOIN assistant_attachment approved_image
                ON approved_image.id = image_ref.attachment_id AND approved_image.workspace_id = image_ref.workspace_id
                    AND approved_image.account_id = image_ref.account_id AND approved_image.project_id = image_ref.project_id
                    AND ((approved_image.media_kind = 'PNG' AND approved_image.status = 'approved')
                        OR (approved_image.media_kind = 'EDIT' AND approved_image.status = 'edit_approved'))
                    AND approved_image.approved_digest IS NOT NULL
            """
            + AttachmentImageEditProof.joins("approved_image");

    private static final int MAX_ALT = 160;

    private AssistantArtifactImages() {
    }

    static Reference input(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 2
            || !value.path("attachmentId").isTextual() || !value.path("altText").isTextual()) {
            throw invalid();
        }
        String attachmentId = value.path("attachmentId").textValue();
        String alt = value.path("altText").textValue();
        if (!canonicalUuid(attachmentId) || alt.length() > MAX_ALT
            || !StandardCharsets.UTF_8.newEncoder().canEncode(alt)
            || alt.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return new Reference(attachmentId, alt.strip());
    }

    static String encode(ObjectMapper mapper, Reference reference) {
        try {
            return mapper.writeValueAsString(reference);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    static Reference readStored(ObjectMapper mapper, String content) {
        try {
            return input(AssistantArtifacts.jsonReader(mapper).readTree(content));
        } catch (JsonProcessingException | AssistantApiException failure) {
            throw unavailable();
        }
    }

    static Image image(ObjectMapper mapper, String content, Source source) {
        var reference = readStored(mapper, content);
        if (source == null || !reference.attachmentId().equals(source.id())
            || source.size() < 1 || source.size() > 1048576
            || source.width() < 1 || source.width() > 2048
            || source.height() < 1 || source.height() > 2048
            || source.width() * source.height() > 2_000_000) {
            throw unavailable();
        }
        return new Image(source.id(), reference.altText(), source.size(), source.width(),
            source.height(), source.editProof() == null ? null : source.editProof().origin());
    }

    /**
     * Maps the owner-scoped approval projection or returns null when unavailable.
     *
     * @param row an artifact query with the image source columns
     * @return safe PNG coordinates or null
     * @throws SQLException when the projection is unavailable
     */
    public static Source source(ResultSet row) throws SQLException {
        String id = row.getString("image_attachment_id");
        if (id == null) {
            return null;
        }
        Proof proof = AttachmentImageEditProof.read(row);
        if ("EDIT".equals(row.getString("image_media_kind")) != (proof != null)) {
            return null;
        }
        return new Source(id, row.getInt("image_size"), row.getInt("image_width"),
            row.getInt("image_height"), proof);
    }

    private static boolean canonicalUuid(String value) {
        try {
            return value.length() == 36 && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Choose an approved project image and up to 160 characters of alternative text.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The private image is temporarily unavailable.");
    }

    record Reference(String attachmentId, String altText) {

        @Override
        public String toString() {
            return "Reference[protected]";
        }
    }

    /**
     * Approved PNG dimensions for a same-scope artifact version.
     *
     * @param id owner attachment identifier
     * @param size approved byte length
     * @param width width in pixels
     * @param height height in pixels
     * @param editProof private authorization proof for a derived PNG
     */
    public record Source(String id, int size, int width, int height, Proof editProof) {

        /**
         * Creates metadata for an original PNG.
         *
         * @param id owner attachment identifier
         * @param size approved byte length
         * @param width width in pixels
         * @param height height in pixels
         */
        public Source(String id, int size, int width, int height) {
            this(id, size, width, height, null);
        }

        /**
         * Rechecks both the visible PNG and the original authorization if it is an edit.
         *
         * @param journal independent revocation markers
         * @param account owning account
         * @param workspace owning workspace
         * @return true if the protected image remains readable
         */
        public boolean allows(AssistantDeletionJournal journal, String account, String workspace) {
            return !journal.blocksAttachment(account, workspace, id)
                && (editProof == null || editProof.allows(journal, account, workspace));
        }
    }

    /**
     * Owner-visible attachment reference and alternative text.
     *
     * @param attachmentId original approved project PNG
     * @param altText author's plain alternative description
     * @param size approved PNG byte length
     * @param width width in pixels
     * @param height height in pixels
     * @param localEdit original source and exact operation for an approved local edit
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Image(String attachmentId, String altText, int size, int width, int height,
        Origin localEdit) {

        /**
         * Creates owner-visible metadata for an original PNG.
         *
         * @param attachmentId original attachment identifier
         * @param altText author's alternative text
         * @param size approved byte length
         * @param width width in pixels
         * @param height height in pixels
         */
        public Image(String attachmentId, String altText, int size, int width, int height) {
            this(attachmentId, altText, size, width, height, null);
        }

        SharedImage shared() {
            return new SharedImage(altText, size, width, height, localEdit == null ? null : true);
        }

        @Override
        public String toString() {
            return "Image[protected]";
        }
    }

    /**
     * Approved immutable PNG metadata visible under a current project role.
     *
     * @param altText author's plain alternative description
     * @param size approved PNG byte length
     * @param width width in pixels
     * @param height height in pixels
     * @param localEdit true for a verified local edit, without private source coordinates
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SharedImage(String altText, int size, int width, int height, Boolean localEdit) {

        @Override
        public String toString() {
            return "SharedImage[protected]";
        }
    }
}
