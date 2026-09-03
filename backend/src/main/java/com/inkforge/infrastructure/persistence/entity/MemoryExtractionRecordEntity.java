package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.memory.MemoryExtractionStats;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA persistence entity for memory extraction records (latest per chapter via merge). */
@Entity
@Table(name = "memory_extraction_record")
public class MemoryExtractionRecordEntity {

    @EmbeddedId
    private NovelChapterId id;

    @Column(name = "status")
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "model")
    private String model;

    @Column(name = "stats")
    @Convert(converter = JsonbConverters.StatsConverter.class)
    private MemoryExtractionStats stats;

    @Column(name = "created_at")
    private Instant createdAt;

    public NovelChapterId getId() {
        return id;
    }

    public void setId(NovelChapterId id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public MemoryExtractionStats getStats() {
        return stats;
    }

    public void setStats(MemoryExtractionStats stats) {
        this.stats = stats;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
