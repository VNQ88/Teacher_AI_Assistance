-- liquibase formatted sql
-- changeset VNQ88:5-create-vector-index

-- 1. HNSW Index cho cột embedding (cosine similarity)
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding_ivfflat
    ON document_chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 200)
    WHERE embedding IS NOT NULL;

COMMENT ON INDEX idx_document_chunks_embedding_ivfflat
IS 'IVFFLAT index for cosine similarity search on 3072-d embeddings';

-- 2. Composite filter: môn học + tài liệu
-- (Đã xóa 2 index đơn lẻ vì v1.0 đã tạo rồi)
CREATE INDEX IF NOT EXISTS idx_document_chunks_subject_document
    ON document_chunks (subject_id, document_id);

-- 3. Helper function: chuyển cosine distance → cosine similarity
CREATE OR REPLACE FUNCTION cosine_similarity(a vector, b vector)
RETURNS float
AS $$
BEGIN
RETURN 1 - (a <=> b);
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE;

COMMENT ON FUNCTION cosine_similarity(vector, vector)
IS 'Calculate cosine similarity between two vectors. Returns value between 0 (opposite) and 1 (identical)';