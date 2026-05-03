-- Add subject owner field and backfill existing rows with default owner.
ALTER TABLE subjects
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

UPDATE subjects
SET owner_id = 2
WHERE owner_id IS NULL;

