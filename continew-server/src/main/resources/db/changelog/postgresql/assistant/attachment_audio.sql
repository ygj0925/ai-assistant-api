-- liquibase formatted sql

-- changeset yuanguangji:assistant-attachment-audio-1
ALTER TABLE assistant_attachment
    ADD COLUMN media_kind VARCHAR(4) NOT NULL DEFAULT 'PNG',
    ADD COLUMN sample_rate INTEGER,
    ADD COLUMN channel_count SMALLINT,
    ADD COLUMN duration_millis INTEGER;

-- changeset yuanguangji:assistant-attachment-audio-2
ALTER TABLE assistant_attachment DROP CONSTRAINT chk_assistant_attachment_status,
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
        ) AND (status NOT IN ('approved','wave_approved') OR approved_digest IS NOT NULL)
    );
