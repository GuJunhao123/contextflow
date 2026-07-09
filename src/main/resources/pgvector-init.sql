-- Apply explicitly with psql to the database in VECTOR_JDBC_URL; requires pgvector installed.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS cf_index_manifest (
    corpus_version text PRIMARY KEY,
    dimensions integer NOT NULL CHECK (dimensions > 0 AND dimensions <= 16000),
    embedding_identity text NOT NULL,
    chunk_count integer NOT NULL CHECK (chunk_count > 0)
);
-- Unconstrained vector allows configured dimensions; application validates every response and manifest.
-- Exact cosine search intentionally avoids an approximate index's recall/filtering pitfalls for this demo.
CREATE TABLE IF NOT EXISTS cf_chunks (
    corpus_version text NOT NULL,
    chunk_id text NOT NULL,
    model text NOT NULL,
    year_from integer NOT NULL,
    year_to integer NOT NULL,
    embedding vector NOT NULL,
    PRIMARY KEY (corpus_version, chunk_id),
    CHECK (year_from <= year_to)
);
CREATE INDEX IF NOT EXISTS cf_chunks_filter ON cf_chunks(corpus_version, model, year_from, year_to);
