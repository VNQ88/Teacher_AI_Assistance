-- Manual versioned SQL for Hierarchical RAG document node alignment.
-- Liquibase is disabled in dev; run manually in environments that do not use Hibernate ddl-auto.

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS hierarchy_object_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS chunks_object_key VARCHAR(500);

CREATE TABLE IF NOT EXISTS document_nodes
(
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT                              NOT NULL REFERENCES documents ON DELETE CASCADE,
    parent_id      BIGINT REFERENCES document_nodes ON DELETE CASCADE,
    subject_id     BIGINT                              NOT NULL,
    node_key       VARCHAR(100)                        NOT NULL,
    node_type      VARCHAR(30)                         NOT NULL,
    level          INTEGER                             NOT NULL,
    title          VARCHAR(500),
    section_path   TEXT,
    order_index    INTEGER                             NOT NULL,
    page_from      INTEGER,
    page_to        INTEGER,
    content        TEXT,
    metadata_jsonb JSONB     DEFAULT '{}'::jsonb       NOT NULL,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE document_nodes
    ADD COLUMN IF NOT EXISTS node_key VARCHAR(100);

UPDATE document_nodes
SET node_key = 'legacy-' || id
WHERE node_key IS NULL OR btrim(node_key) = '';

ALTER TABLE document_nodes
    ALTER COLUMN node_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_nodes_document_node_key
    ON document_nodes(document_id, node_key);

CREATE INDEX IF NOT EXISTS idx_document_nodes_doc_parent_order
    ON document_nodes(document_id, parent_id, order_index);

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS node_id BIGINT REFERENCES document_nodes ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS parent_node_id BIGINT REFERENCES document_nodes ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS section_path TEXT,
    ADD COLUMN IF NOT EXISTS page_from INTEGER,
    ADD COLUMN IF NOT EXISTS page_to INTEGER,
    ADD COLUMN IF NOT EXISTS source_order INTEGER;

CREATE INDEX IF NOT EXISTS idx_chunk_node
    ON document_chunks(node_id);

CREATE INDEX IF NOT EXISTS idx_chunk_parent_node
    ON document_chunks(parent_node_id);

CREATE INDEX IF NOT EXISTS idx_chunk_doc_source_order
    ON document_chunks(document_id, source_order);
