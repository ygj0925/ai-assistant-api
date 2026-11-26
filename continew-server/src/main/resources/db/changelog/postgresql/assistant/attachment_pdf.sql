-- liquibase formatted sql

-- changeset yuanguangji:assistant-attachment-pdf-1
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
            OR (media_kind = 'PDF' AND width = 0 AND height = 0
                AND sample_rate IS NULL AND channel_count IS NULL AND duration_millis IS NULL
                AND ((status = 'revoked' AND size_bytes = 0)
                    OR (status = 'pdf_approved' AND size_bytes BETWEEN 1 AND 4096)
                    OR (status IN ('pdf_receiving','pdf_quarantined','pdf_rejected')
                        AND size_bytes BETWEEN 9 AND 32768)))
        ) AND (status NOT IN ('approved','wave_approved','pdf_approved') OR approved_digest IS NOT NULL)
    );
