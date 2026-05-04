-- liquibase formatted sql
-- changeset VNQ88:8-drop-unused-document-artifact-columns

ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS uk_documents_docling_object_key,
    DROP CONSTRAINT IF EXISTS uk_documents_structure_object_key;

DROP INDEX IF EXISTS uk_documents_docling_object_key;
DROP INDEX IF EXISTS uk_documents_structure_object_key;

ALTER TABLE documents
    DROP COLUMN IF EXISTS docling_object_key,
    DROP COLUMN IF EXISTS structure_object_key;
