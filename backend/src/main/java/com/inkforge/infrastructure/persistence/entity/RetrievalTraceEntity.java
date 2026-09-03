package com.inkforge.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** JPA persistence entity for retrieval traces (P3-A V2 table, no new migration). */
@Entity
@Table(name = "retrieval_trace")
public class RetrievalTraceEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "novel_id")
    private String novelId;

    @Column(name = "generation_id")
    private String generationId;

    @Column(name = "queries")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> queries;

    @Column(name = "pipeline")
    @Convert(converter = JsonbConverters.RetrievalPipelineConverter.class)
    private Map<String, List<com.inkforge.retrieval.RetrievalResult>> pipeline;

    @Column(name = "created_at")
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNovelId() {
        return novelId;
    }

    public void setNovelId(String novelId) {
        this.novelId = novelId;
    }

    public String getGenerationId() {
        return generationId;
    }

    public void setGenerationId(String generationId) {
        this.generationId = generationId;
    }

    public List<String> getQueries() {
        return queries;
    }

    public void setQueries(List<String> queries) {
        this.queries = queries;
    }

    public Map<String, List<com.inkforge.retrieval.RetrievalResult>> getPipeline() {
        return pipeline;
    }

    public void setPipeline(Map<String, List<com.inkforge.retrieval.RetrievalResult>> pipeline) {
        this.pipeline = pipeline;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
