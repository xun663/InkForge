-- InkForge P3-A: PostgreSQL persistence schema for P1/P2 data + P3 tables.
-- List-like domain fields use JSONB (key_events, aliases, participants, ...) —
-- deliberately not normalized into dozens of tables; retrieval happens via memory_chunk.
-- No HNSW index yet: it is created in P3-C once embedding/distance/top-K are settled.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE novel (
    id               VARCHAR(64) PRIMARY KEY,
    title            VARCHAR(512) NOT NULL,
    source_file_name VARCHAR(512),
    chapter_count    INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ
);

CREATE TABLE chapter (
    novel_id   VARCHAR(64) NOT NULL REFERENCES novel (id),
    ordinal    INT NOT NULL,
    chapter_no INT,
    title      VARCHAR(512),
    content    TEXT,
    char_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (novel_id, ordinal)
);

CREATE TABLE chapter_summary (
    novel_id            VARCHAR(64) NOT NULL REFERENCES novel (id),
    chapter_ordinal     INT NOT NULL,
    summary             TEXT,
    key_events          JSONB,
    characters          JSONB,
    locations           JSONB,
    important_items     JSONB,
    unresolved_threads  JSONB,
    created_at          TIMESTAMPTZ,
    PRIMARY KEY (novel_id, chapter_ordinal)
);

CREATE TABLE character (
    id            VARCHAR(64) PRIMARY KEY,
    novel_id      VARCHAR(64) NOT NULL REFERENCES novel (id),
    name          VARCHAR(128) NOT NULL,
    aliases       JSONB,
    first_chapter INT NOT NULL,
    last_chapter  INT NOT NULL,
    status        VARCHAR(16),
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ
);

CREATE TABLE character_fact (
    id                VARCHAR(64) PRIMARY KEY,
    character_id      VARCHAR(64) NOT NULL REFERENCES character (id),
    category          VARCHAR(32),
    attribute         VARCHAR(64),
    value             VARCHAR(512),
    target_character  VARCHAR(128),
    status            VARCHAR(16),
    valid_from        INT,
    valid_until       INT,
    confidence        DOUBLE PRECISION,
    source_chapter    INT,
    source_quote      VARCHAR(1024),
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ
);
CREATE INDEX idx_character_fact_character ON character_fact (character_id);

CREATE TABLE story_event (
    id              VARCHAR(64) PRIMARY KEY,
    novel_id        VARCHAR(64) NOT NULL REFERENCES novel (id),
    chapter_ordinal INT NOT NULL,
    title           VARCHAR(256),
    description     TEXT,
    participants    JSONB,
    location        VARCHAR(256),
    consequences    JSONB,
    importance      INT,
    source_quote    VARCHAR(1024),
    created_at      TIMESTAMPTZ
);

-- P3-B/C retrieval projection table: schema now, code in later stages.
-- embedding dimension must match inkforge.embedding.dimension (guarded at startup).
CREATE TABLE memory_chunk (
    id              VARCHAR(64) PRIMARY KEY,
    novel_id        VARCHAR(64) NOT NULL REFERENCES novel (id),
    memory_type     VARCHAR(32) NOT NULL,
    source_id       VARCHAR(64),
    chapter_ordinal INT NOT NULL,
    text            TEXT,
    search_text     TEXT,
    embedding       vector(1024),
    created_at      TIMESTAMPTZ
);

CREATE TABLE memory_extraction_record (
    novel_id        VARCHAR(64) NOT NULL REFERENCES novel (id),
    chapter_ordinal INT NOT NULL,
    status          VARCHAR(16),
    error_message   TEXT,
    model           VARCHAR(128),
    stats           JSONB,
    created_at      TIMESTAMPTZ,
    PRIMARY KEY (novel_id, chapter_ordinal)
);

CREATE TABLE generation_log (
    generation_id       VARCHAR(64) PRIMARY KEY,
    novel_id            VARCHAR(64),
    provider            VARCHAR(64),
    model               VARCHAR(128),
    prompt_tokens       INT,
    completion_tokens   INT,
    latency_ms          BIGINT,
    estimated_cost_usd  NUMERIC(12, 6),
    status              VARCHAR(16),
    error_message       TEXT,
    type                VARCHAR(32),
    created_at          TIMESTAMPTZ
);

-- P3-E observability table: schema now, code later.
CREATE TABLE retrieval_trace (
    id            VARCHAR(64) PRIMARY KEY,
    novel_id      VARCHAR(64) NOT NULL REFERENCES novel (id),
    generation_id VARCHAR(64),
    queries       JSONB,
    pipeline      JSONB,
    created_at    TIMESTAMPTZ
);
