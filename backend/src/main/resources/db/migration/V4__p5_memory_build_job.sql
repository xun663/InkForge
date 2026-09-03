-- InkForge P5-A: MemoryBuildJob persistence.
-- Job 生命周期（状态/进度/failedOrdinals）存这里；每章事实记录仍在 memory_extraction_record。
CREATE TABLE IF NOT EXISTS memory_build_job (
    job_id           VARCHAR(64) PRIMARY KEY,
    novel_id         VARCHAR(64) NOT NULL REFERENCES novel (id),
    status           VARCHAR(32) NOT NULL,
    total_chapters   INT NOT NULL DEFAULT 0,
    success_chapters INT NOT NULL DEFAULT 0,
    failed_chapters  INT NOT NULL DEFAULT 0,
    current_ordinal  INT NOT NULL DEFAULT -1,
    failed_ordinals  JSONB,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_memory_build_job_novel_status
    ON memory_build_job (novel_id, status);

-- 并发保护：同一 novel 同时只允许一个 PENDING/RUNNING Job（DB 级兜底）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_build_job_active_novel
    ON memory_build_job (novel_id)
    WHERE status IN ('PENDING', 'RUNNING');
