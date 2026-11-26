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

package top.continew.admin.assistant.attachment;

import top.continew.admin.assistant.privacy.AssistantDeletionJournal;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads a local edit's current same-scope source and binds its immutable authorization proof.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
public final class AttachmentImageEditProof {

    /**
     * Internal projection with no pixel payload.
     */
    public static final String PROJECTION = """
        image_edit.source_attachment_id AS image_edit_source_id,
        image_edit.membership_revision AS image_edit_membership,
        image_edit.source_digest AS image_edit_digest,
        image_edit.algorithm AS image_edit_algorithm,
        image_edit.crop_x AS image_edit_x, image_edit.crop_y AS image_edit_y,
        image_edit.crop_width AS image_edit_width, image_edit.crop_height AS image_edit_height,
        image_edit.rotation AS image_edit_rotation,
        CASE WHEN image_edit_source.id IS NOT NULL AND image_edit_member.account_id IS NOT NULL
            THEN TRUE ELSE FALSE END AS image_edit_source_current
        """;

    private AttachmentImageEditProof() {
    }

    /**
     * Adds the optional edit record and its exact, currently approved original PNG.
     *
     * @param outputAlias trusted attachment alias from the calling query
     * @return scoped lineage joins
     */
    public static String joins(String outputAlias) {
        return """
            LEFT JOIN assistant_attachment_image_edit image_edit
                ON image_edit.output_attachment_id = %1$s.id
                AND image_edit.account_id = %1$s.account_id AND image_edit.workspace_id = %1$s.workspace_id
                AND image_edit.project_id = %1$s.project_id
            LEFT JOIN assistant_attachment image_edit_source
                ON image_edit.source_attachment_id = image_edit_source.id
                AND image_edit.account_id = image_edit_source.account_id
                AND image_edit.workspace_id = image_edit_source.workspace_id
                AND image_edit.project_id = image_edit_source.project_id
                AND image_edit_source.media_kind = 'PNG' AND image_edit_source.status = 'approved'
                AND image_edit_source.approved_digest = image_edit.source_digest
                AND NOT EXISTS (SELECT 1 FROM assistant_attachment_image_edit chained
                    WHERE chained.output_attachment_id = image_edit_source.id)
            LEFT JOIN assistant_workspace_member image_edit_member
                ON image_edit_member.account_id = image_edit.account_id
                AND image_edit_member.workspace_id = image_edit.workspace_id
                AND image_edit_member.membership_revision = image_edit.membership_revision
            """
            .formatted(outputAlias);
    }

    /**
     * Loads the optional proof for this projected attachment.
     *
     * @param row result set with the internal edit columns
     * @return edit binding, or null for an original attachment
     * @throws SQLException when the projection is unavailable
     */
    public static Proof read(ResultSet row) throws SQLException {
        String original = row.getString("image_edit_source_id");
        if (original == null) {
            return null;
        }
        var origin = new Origin(row.getString("image_edit_algorithm"), original,
            row.getInt("image_edit_x"), row.getInt("image_edit_y"), row.getInt("image_edit_width"),
            row.getInt("image_edit_height"), row.getInt("image_edit_rotation"));
        return new Proof(origin, row.getString("image_edit_membership"),
            row.getString("image_edit_digest"), row.getBoolean("image_edit_source_current"));
    }

    /**
     * Owner-visible local edit coordinates.
     *
     * @param algorithm immutable render version
     * @param sourceAttachmentId original private PNG identifier
     * @param x zero-indexed left crop coordinate
     * @param y zero-indexed top crop coordinate
     * @param width width of the original crop
     * @param height height of the original crop
     * @param rotation clockwise quarter-turn in degrees
     */
    public record Origin(String algorithm, String sourceAttachmentId, int x, int y, int width,
        int height, int rotation) {
    }

    /**
     * Lookup proof kept internal to attachment, artifact and export paths.
     *
     * @param origin established edit description
     * @param membershipRevision original owning workspace membership
     * @param sourceDigest approved immutable input fingerprint
     * @param sourceCurrent whether the source and membership matched in the database snapshot
     */
    public record Proof(Origin origin, String membershipRevision, String sourceDigest,
        boolean sourceCurrent) {

        /**
         * Rechecks durable deletion and workspace departure after the database snapshot was read.
         *
         * @param journal independent deletion and scope journal
         * @param account owning account
         * @param workspace owning workspace
         * @return whether the original source is still eligible in the established membership
         */
        public boolean allows(AssistantDeletionJournal journal, String account, String workspace) {
            return sourceCurrent && !journal.blocksAttachment(account, workspace,
                origin.sourceAttachmentId())
                && !journal.blocksWorkspaceMembership(account, workspace, membershipRevision);
        }

        @Override
        public String toString() {
            return "Proof[protected]";
        }
    }
}
