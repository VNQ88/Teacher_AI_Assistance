-- Manual SQL to enforce one document per subject.
-- Existing production data must not contain duplicate subject_id values before running this.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_documents_subject_id'
    ) THEN
        ALTER TABLE documents
            ADD CONSTRAINT uk_documents_subject_id UNIQUE (subject_id);
    END IF;
END $$;
