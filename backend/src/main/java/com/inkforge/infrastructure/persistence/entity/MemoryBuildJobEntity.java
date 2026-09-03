package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.memory.build.MemoryBuildStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** JPA persistence entity for MemoryBuildJob（PostgreSQL profile）。 */
@Entity
@Table(name = "memory_build_job")
public class MemoryBuildJobEntity {

    @Id
    @Column(name = "job_id", length = 64)
    private String jobId;

    @Column(name = "novel_id", length = 64, nullable = false)
    private String novelId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "total_chapters", nullable = false)
    private int totalChapters;

    @Column(name = "success_chapters", nullable = false)
    private int successChapters;

    @Column(name = "failed_chapters", nullable = false)
    private int failedChapters;

    @Column(name = "current_ordinal", nullable = false)
    private int currentOrdinal;

    @Column(name = "failed_ordinals")
    @Convert(converter = JsonbConverters.IntegerListConverter.class)
    private List<Integer> failedOrdinals;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getNovelId() { return novelId; }
    public void setNovelId(String novelId) { this.novelId = novelId; }
    public MemoryBuildStatus getStatus() { return status == null ? null : MemoryBuildStatus.valueOf(status); }
    public void setStatus(MemoryBuildStatus status) { this.status = status == null ? null : status.name(); }
    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }
    public int getSuccessChapters() { return successChapters; }
    public void setSuccessChapters(int successChapters) { this.successChapters = successChapters; }
    public int getFailedChapters() { return failedChapters; }
    public void setFailedChapters(int failedChapters) { this.failedChapters = failedChapters; }
    public int getCurrentOrdinal() { return currentOrdinal; }
    public void setCurrentOrdinal(int currentOrdinal) { this.currentOrdinal = currentOrdinal; }
    public List<Integer> getFailedOrdinals() { return failedOrdinals; }
    public void setFailedOrdinals(List<Integer> failedOrdinals) { this.failedOrdinals = failedOrdinals; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
