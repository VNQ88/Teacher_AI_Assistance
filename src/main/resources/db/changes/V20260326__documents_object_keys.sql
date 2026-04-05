-- Manual migration for document storage keys (without Liquibase)
-- Date: 2026-03-26

ALTER TABLE documents
    DROP COLUMN IF EXISTS file_url;

ALTER TABLE documents
    DROP COLUMN IF EXISTS object_key;

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS original_object_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS markdown_object_key VARCHAR(500);

-- Backfill note:
-- If old column object_key existed and was dropped only after data copy,
-- map it into original_object_key before dropping in production.

-- Enforce non-null for original key after backfill in production rollout.
ALTER TABLE documents
    ALTER COLUMN original_object_key SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_documents_original_object_key'
    ) THEN
        ALTER TABLE documents
            ADD CONSTRAINT uk_documents_original_object_key UNIQUE (original_object_key);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_documents_markdown_object_key'
    ) THEN
        ALTER TABLE documents
            ADD CONSTRAINT uk_documents_markdown_object_key UNIQUE (markdown_object_key);
    END IF;
END
$$;

