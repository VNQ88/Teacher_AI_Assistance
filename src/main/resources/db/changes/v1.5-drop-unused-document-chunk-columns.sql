-- liquibase formatted sql
-- changeset VNQ88:7-drop-unused-document-chunk-columns

DROP INDEX IF EXISTS idx_chunk_subject_classroom;

ALTER TABLE document_chunks
    DROP COLUMN IF EXISTS content_hash,
    DROP COLUMN IF EXISTS metadata,
    DROP COLUMN IF EXISTS classroom_id,
    DROP COLUMN IF EXISTS page_from,
    DROP COLUMN IF EXISTS page_to;

