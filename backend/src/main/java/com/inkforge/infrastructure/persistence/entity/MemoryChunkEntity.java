package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.retrieval.MemoryChunkType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA persistence entity for retrieval chunks. The {@code embedding} column is
 * deliberately NOT mapped here — pgvector operations belong to P3-C's VectorRetriever
 * (JdbcTemplate), keeping ORM vector-mapping risk out of the persistence layer.
 */
@Entity
@Table(name = "memory_chunk")
public class MemoryChunkEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "novel_id")
    private String novelId;

    @Column(name = "memory_type")
    @Enumerated(EnumType.STRING)
    private MemoryChunkType memoryType;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "chapter_ordinal")
    private int chapterOrdinal;

    @Column(name = "text")
    private String text;

    @Column(name = "search_text")
    private String searchText;

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

    public MemoryChunkType getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(MemoryChunkType memoryType) {
        this.memoryType = memoryType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public int getChapterOrdinal() {
        return chapterOrdinal;
    }

    public void setChapterOrdinal(int chapterOrdinal) {
        this.chapterOrdinal = chapterOrdinal;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
