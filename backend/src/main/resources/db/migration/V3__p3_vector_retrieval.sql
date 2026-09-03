-- P3-C: enable pgvector for retrieval.
-- 1. content hash column: chunk embeddings are only valid when the stored hash matches
--    the CURRENT searchText hash (id stable, content may change on re-projection).
-- 2. HNSW index over cosine distance — matches the <=> operator used by
--    PostgresVectorRetriever (vector_cosine_ops). Default parameters, no tuning yet.

ALTER TABLE memory_chunk ADD COLUMN IF NOT EXISTS embedding_content_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_memory_chunk_embedding_hnsw
    ON memory_chunk USING hnsw (embedding vector_cosine_ops);
