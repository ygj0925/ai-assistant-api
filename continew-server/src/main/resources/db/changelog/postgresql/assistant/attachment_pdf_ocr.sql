-- liquibase formatted sql

-- changeset yuanguangji:assistant-attachment-pdf-ocr-1
ALTER TABLE assistant_attachment ADD COLUMN pdf_ocr BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE assistant_attachment ADD CONSTRAINT chk_assistant_attachment_pdf_ocr CHECK (
    NOT pdf_ocr OR (media_kind = 'PDF' AND status IN ('pdf_approved', 'revoked'))
);
