package com.inkforge.memory.build;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 全量 Memory Build Job（P5-A）。
 *
 * <p>职责边界：本类只承载「这一批全量构建做到哪里 / 什么状态」。每一章的
 * 事实记录（SUCCESS/FAILED）仍由 {@code MemoryExtractionRecord} 承担——那是 Resume 的
 * Ground Truth，比本 Job 的 currentOrdinal 更可靠。
 *
 * <p>状态机（非法转换抛 IllegalStateException）：
 * <pre>
 * PENDING → RUNNING
 * RUNNING → PAUSED / COMPLETED / PARTIAL_FAILED / CANCELLED
 * PAUSED  → RUNNING / CANCELLED
 * </pre>
 */
public class MemoryBuildJob {

    private final String jobId;
    private final String novelId;
    private MemoryBuildStatus status;
    private final int totalChapters;
    private int successChapters;
    private int failedChapters;
    private int currentOrdinal;
    private final List<Integer> failedOrdinals;
    private final Instant createdAt;
    private Instant updatedAt;

    public MemoryBuildJob(String novelId, int totalChapters) {
        this(UUID.randomUUID().toString(), novelId, MemoryBuildStatus.PENDING, totalChapters,
                0, 0, -1, new ArrayList<>(), Instant.now(), Instant.now());
    }

    public MemoryBuildJob(String jobId, String novelId, MemoryBuildStatus status, int totalChapters,
                          int successChapters, int failedChapters, int currentOrdinal,
                          List<Integer> failedOrdinals, Instant createdAt, Instant updatedAt) {
        this.jobId = jobId;
        this.novelId = novelId;
        this.status = status;
        this.totalChapters = totalChapters;
        this.successChapters = successChapters;
        this.failedChapters = failedChapters;
        this.currentOrdinal = currentOrdinal;
        this.failedOrdinals = new ArrayList<>(failedOrdinals);
        this.failedOrdinals.sort(Comparator.naturalOrder());
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ---------- 状态机 ----------

    private void transition(MemoryBuildStatus next, Set<MemoryBuildStatus> allowedFrom) {
        if (!allowedFrom.contains(status)) {
            throw new IllegalStateException(
                    "非法状态转换: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public void start() {
        transition(MemoryBuildStatus.RUNNING, Set.of(MemoryBuildStatus.PENDING, MemoryBuildStatus.PAUSED));
    }

    public void pause() {
        transition(MemoryBuildStatus.PAUSED, EnumSet.of(MemoryBuildStatus.RUNNING));
    }

    public void resume() {
        transition(MemoryBuildStatus.RUNNING, EnumSet.of(MemoryBuildStatus.PAUSED));
    }

    /** 重试失败章节：PARTIAL_FAILED（有失败）→ RUNNING，重新跑失败章节。 */
    public void retry() {
        transition(MemoryBuildStatus.RUNNING, EnumSet.of(MemoryBuildStatus.PARTIAL_FAILED));
    }

    public void cancel() {
        transition(MemoryBuildStatus.CANCELLED,
                EnumSet.of(MemoryBuildStatus.PENDING, MemoryBuildStatus.RUNNING, MemoryBuildStatus.PAUSED));
    }

    public void finishSuccess() {
        transition(MemoryBuildStatus.COMPLETED, EnumSet.of(MemoryBuildStatus.RUNNING));
    }

    public void finishPartialFailed() {
        transition(MemoryBuildStatus.PARTIAL_FAILED, EnumSet.of(MemoryBuildStatus.RUNNING));
    }

    // ---------- 进度 ----------

    /**
     * 处理完一个章节后更新进度（对 initial / resume / retry-failed 都正确）：
     * success → successChapters++ 并从 failedOrdinals 移除；失败 → 加入 failedOrdinals（去重）。
     * failedChapters 恒等于 failedOrdinals.size()——避免 resume/retry 时计数漂移。
     */
    public void recordChapter(boolean success, int ordinal) {
        if (success) {
            successChapters++;
            failedOrdinals.remove((Integer) ordinal);
        } else if (!failedOrdinals.contains(ordinal)) {
            failedOrdinals.add(ordinal);
            failedOrdinals.sort(Comparator.naturalOrder());
        }
        this.failedChapters = failedOrdinals.size();
        this.currentOrdinal = ordinal;
        this.updatedAt = Instant.now();
    }

    public void touchProgress(int ordinal) {
        this.currentOrdinal = ordinal;
        this.updatedAt = Instant.now();
    }

    public double progress() {
        return totalChapters == 0 ? 0 : Math.min(1.0, (double) (successChapters + failedChapters) / totalChapters);
    }

    // ---------- getters ----------

    public String jobId() { return jobId; }
    public String novelId() { return novelId; }
    public MemoryBuildStatus status() { return status; }
    public int totalChapters() { return totalChapters; }
    public int successChapters() { return successChapters; }
    public int failedChapters() { return failedChapters; }
    public int currentOrdinal() { return currentOrdinal; }
    public List<Integer> failedOrdinals() { return List.copyOf(failedOrdinals); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    // 供持久化映射
    public void setStatus(MemoryBuildStatus status) { this.status = status; }
    public void setSuccessChapters(int n) { this.successChapters = n; }
    public void setFailedChapters(int n) { this.failedChapters = n; }
    public void setCurrentOrdinal(int n) { this.currentOrdinal = n; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
}
