ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS hierarchy_object_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS chunks_object_key VARCHAR(500);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_documents_hierarchy_object_key'
    ) THEN
        ALTER TABLE documents
            ADD CONSTRAINT uk_documents_hierarchy_object_key UNIQUE (hierarchy_object_key);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_documents_chunks_object_key'
    ) THEN
        ALTER TABLE documents
            ADD CONSTRAINT uk_documents_chunks_object_key UNIQUE (chunks_object_key);
    END IF;
END $$;
