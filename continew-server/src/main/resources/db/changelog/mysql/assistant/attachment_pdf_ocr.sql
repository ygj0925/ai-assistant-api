-- liquibase formatted sql

-- changeset yuanguangji:assistant-attachment-pdf-ocr-1
ALTER TABLE assistant_attachment
    ADD COLUMN pdf_ocr BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_assistant_attachment_pdf_ocr CHECK (
        pdf_ocr IN (0, 1)
        AND (pdf_ocr = 0 OR (media_kind = 'PDF' AND status IN ('pdf_approved', 'revoked')))
    );
