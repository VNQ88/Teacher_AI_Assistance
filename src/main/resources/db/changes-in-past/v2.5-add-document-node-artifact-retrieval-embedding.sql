-- Manual SQL for coarse-to-fine factual QA artifact retrieval embeddings.
-- Liquibase is disabled; run this on existing databases before enabling runtime retrieval code.

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE document_node_artifacts
    ADD COLUMN IF NOT EXISTS retrieval_text TEXT,
    ADD COLUMN IF NOT EXISTS retrieval_text_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS embedding vector(1024),
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(120),
    ADD COLUMN IF NOT EXISTS embedding_dimensions INTEGER;

COMMENT ON COLUMN document_node_artifacts.retrieval_text IS
    'Text used to generate the coarse retrieval embedding for this artifact.';

COMMENT ON COLUMN document_node_artifacts.retrieval_text_hash IS
    'Hash of retrieval_text used to skip unchanged artifact embedding work.';

COMMENT ON COLUMN document_node_artifacts.embedding IS
    'Coarse retrieval embedding generated from retrieval_text.';

COMMENT ON COLUMN document_node_artifacts.embedding_model IS
    'Embedding model used for artifact coarse retrieval embedding.';

COMMENT ON COLUMN document_node_artifacts.embedding_dimensions IS
    'Embedding vector dimension used for artifact coarse retrieval embedding.';

CREATE INDEX IF NOT EXISTS idx_node_artifacts_embedding_hnsw
    ON document_node_artifacts USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_node_artifacts_embedding_model
    ON document_node_artifacts (embedding_model, embedding_dimensions)
    WHERE embedding IS NOT NULL;
