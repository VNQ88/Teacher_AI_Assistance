-- Manual versioned SQL for document enrichment precompute artifacts.

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS enrichment_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN IF NOT EXISTS enrichment_started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS enrichment_completed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS enrichment_error TEXT;

ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS documents_status_check;

ALTER TABLE documents
    ADD CONSTRAINT documents_status_check
        CHECK (status IN ('UPLOADED', 'PARSING', 'CHUNKING', 'EMBEDDING', 'READY', 'FULL_USE', 'FAILED'));

CREATE TABLE IF NOT EXISTS document_node_artifacts
(
    id               BIGSERIAL PRIMARY KEY,
    created_at       TIMESTAMP(6)              NOT NULL,
    updated_at       TIMESTAMP(6)              NOT NULL,
    document_id      BIGINT                    NOT NULL REFERENCES documents ON DELETE CASCADE,
    document_node_id BIGINT                    NOT NULL REFERENCES document_nodes ON DELETE CASCADE,
    artifact_type    VARCHAR(40)               NOT NULL,
    status           VARCHAR(30)               NOT NULL,
    prompt_version   VARCHAR(80)               NOT NULL,
    model            VARCHAR(120)              NOT NULL,
    source_hash      VARCHAR(128)              NOT NULL,
    content_jsonb    JSONB DEFAULT '{}'::jsonb NOT NULL,
    error_message    TEXT,
    token_count      INTEGER,
    CONSTRAINT uk_node_artifact_version
        UNIQUE (document_node_id, artifact_type, prompt_version, model, source_hash)
);

CREATE INDEX IF NOT EXISTS idx_node_artifacts_document_type_status
    ON document_node_artifacts (document_id, artifact_type, status);

CREATE INDEX IF NOT EXISTS idx_node_artifacts_node_type
    ON document_node_artifacts (document_node_id, artifact_type);

CREATE INDEX IF NOT EXISTS idx_node_artifacts_content_gin
    ON document_node_artifacts USING gin (content_jsonb);
