-- P6: Continuation Modes planning layer (StoryPlan + PlotThread).
-- 严格独立于 Story Memory canon 表（character / character_fact / chapter_summary / story_event）。
-- StoryPlan / PlotThread 是规划层数据，不是既定事实。

CREATE TABLE IF NOT EXISTS story_plan (
    plan_id            VARCHAR(64) PRIMARY KEY,
    novel_id           VARCHAR(64) NOT NULL REFERENCES novel (id),
    mode               VARCHAR(16) NOT NULL,
    title              VARCHAR(512),
    summary            TEXT,
    goal               TEXT,
    expected_arc       TEXT,
    steps              JSONB,
    related_characters JSONB,
    related_threads    JSONB,
    related_events     JSONB,
    user_instruction   TEXT,
    analysis           JSONB,
    status             VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_story_plan_novel ON story_plan (novel_id, created_at);

-- 同一小说同时只允许一个活跃计划（对齐 V4 的 memory_build_job 活跃唯一约束）
CREATE UNIQUE INDEX IF NOT EXISTS uq_story_plan_active_novel
    ON story_plan (novel_id)
    WHERE status IN ('DRAFT', 'CONFIRMED', 'IN_PROGRESS');

CREATE TABLE IF NOT EXISTS plot_thread (
    id                 VARCHAR(64) PRIMARY KEY,
    novel_id           VARCHAR(64) NOT NULL REFERENCES novel (id),
    title              VARCHAR(256) NOT NULL,
    title_normalized   VARCHAR(256) NOT NULL,
    summary            TEXT,
    status             VARCHAR(16) NOT NULL,
    first_seen_chapter INT,
    last_seen_chapter  INT,
    related_characters JSONB,
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_plot_thread_novel_status ON plot_thread (novel_id, status);

-- upsert 匹配键的数据库层兜底（应用层按 title_normalized 先查后写）
CREATE UNIQUE INDEX IF NOT EXISTS uq_plot_thread_novel_title
    ON plot_thread (novel_id, title_normalized);

-- 旧续写记录两列为 NULL；仅在 story_plan 建表之后声明外键
ALTER TABLE generation_log ADD COLUMN IF NOT EXISTS mode    VARCHAR(16);
ALTER TABLE generation_log ADD COLUMN IF NOT EXISTS plan_id VARCHAR(64) REFERENCES story_plan (plan_id);
