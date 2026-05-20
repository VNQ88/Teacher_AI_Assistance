-- Manual SQL to store the exact text used for document chunk embeddings.
-- Existing rows receive an empty default; owner-operated re-index will repopulate chunks.

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS embed_text TEXT NOT NULL DEFAULT '';

COMMENT ON COLUMN document_chunks.embed_text IS
    'Text used to generate the embedding vector; body only, no breadcrumb prefix.';
