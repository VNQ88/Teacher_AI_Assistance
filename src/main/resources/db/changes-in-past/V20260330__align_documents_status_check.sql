-- Align documents.status constraint with current DocumentStatus enum
-- NOTE: Run this script manually on each environment (Liquibase is disabled).

-- 1) Normalize legacy statuses before enforcing new check set.
UPDATE documents
SET status = 'UPLOADED'
WHERE status IN ('PENDING', 'PROCESSING');

-- 2) Drop old check constraint if present.
ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS documents_status_check;

-- 3) Recreate check constraint with current ingestion lifecycle states.
ALTER TABLE documents
    ADD CONSTRAINT documents_status_check
        CHECK (status IN ('UPLOADED', 'PARSING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED'));

-- 4) Optional safety default for new rows.
ALTER TABLE documents
    ALTER COLUMN status SET DEFAULT 'UPLOADED';

