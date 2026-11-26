-- liquibase formatted sql

-- changeset yuanguangji:assistant-attachment-image-edit-1
ALTER TABLE assistant_attachment DROP CHECK chk_assistant_attachment_status,
    ADD CONSTRAINT chk_assistant_attachment_status CHECK (
        size_bytes BETWEEN 0 AND 1048576 AND (
            (media_kind = 'PNG' AND status IN ('receiving','quarantined','approved','rejected','revoked')
                AND width BETWEEN 0 AND 2048 AND height BETWEEN 0 AND 2048
                AND sample_rate IS NULL AND channel_count IS NULL AND duration_millis IS NULL)
            OR (media_kind = 'WAVE' AND status IN
                ('wave_receiving','wave_quarantined','wave_approved','wave_rejected','revoked')
                AND width = 0 AND height = 0
                AND ((status = 'revoked' AND sample_rate IS NULL AND channel_count IS NULL AND duration_millis IS NULL)
                    OR (status <> 'revoked' AND sample_rate IS NOT NULL AND sample_rate BETWEEN 8000 AND 48000
                        AND channel_count IS NOT NULL AND channel_count BETWEEN 1 AND 2
                        AND duration_millis IS NOT NULL AND duration_millis BETWEEN 1 AND 30000)))
            OR (media_kind = 'PDF' AND width = 0 AND height = 0
                AND sample_rate IS NULL AND channel_count IS NULL AND duration_millis IS NULL
                AND ((status = 'revoked' AND size_bytes = 0)
                    OR (status = 'pdf_approved' AND size_bytes BETWEEN 1 AND 4096)
                    OR (status IN ('pdf_receiving','pdf_quarantined','pdf_rejected')
                        AND size_bytes BETWEEN 9 AND 32768)))
            OR (media_kind = 'EDIT' AND sample_rate IS NULL AND channel_count IS NULL AND duration_millis IS NULL
                AND ((status = 'revoked' AND size_bytes = 0 AND width = 0 AND height = 0)
                    OR (status IN ('edit_receiving','edit_quarantined','edit_approved','edit_rejected')
                        AND size_bytes BETWEEN 45 AND 1048576
                        AND width BETWEEN 1 AND 480 AND height BETWEEN 1 AND 480)))
        ) AND (status NOT IN ('approved','wave_approved','pdf_approved','edit_approved') OR approved_digest IS NOT NULL)
    );

-- changeset yuanguangji:assistant-attachment-image-edit-table
CREATE TABLE assistant_attachment_image_edit (
    output_attachment_id VARCHAR(36) PRIMARY KEY,
    source_attachment_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    membership_revision VARCHAR(36) NOT NULL,
    source_digest CHAR(64) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    crop_x INTEGER NOT NULL,
    crop_y INTEGER NOT NULL,
    crop_width INTEGER NOT NULL,
    crop_height INTEGER NOT NULL,
    rotation SMALLINT NOT NULL,
    CONSTRAINT fk_assistant_image_edit_output
        FOREIGN KEY (workspace_id, account_id, project_id, output_attachment_id)
        REFERENCES assistant_attachment (workspace_id, account_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_image_edit_input
        FOREIGN KEY (workspace_id, account_id, project_id, source_attachment_id)
        REFERENCES assistant_attachment (workspace_id, account_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_image_edit_geometry CHECK (
        algorithm = 'local_image_edit_v1' AND output_attachment_id <> source_attachment_id
        AND crop_x BETWEEN 0 AND 479 AND crop_y BETWEEN 0 AND 479
        AND crop_width BETWEEN 1 AND 480 AND crop_height BETWEEN 1 AND 480
        AND crop_x <= 480 - crop_width AND crop_y <= 480 - crop_height
        AND rotation IN (0, 90, 180, 270)
    )
);

-- changeset yuanguangji:assistant-attachment-image-edit-2
CREATE INDEX idx_assistant_image_edit_input ON assistant_attachment_image_edit
    (source_attachment_id, account_id, workspace_id, project_id);
CREATE INDEX idx_assistant_image_edit_membership ON assistant_attachment_image_edit
    (account_id, workspace_id, membership_revision);
