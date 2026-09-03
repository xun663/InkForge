package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.planning.PlotThreadStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** JPA persistence entity for PlotThread（PostgreSQL profile）。 */
@Entity
@Table(name = "plot_thread")
public class PlotThreadEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "novel_id", length = 64, nullable = false)
    private String novelId;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    /** upsert 匹配键（去空白标题），由 mapper 从 title 派生。 */
    @Column(name = "title_normalized", nullable = false, length = 256)
    private String titleNormalized;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "first_seen_chapter")
    private Integer firstSeenChapter;

    @Column(name = "last_seen_chapter")
    private Integer lastSeenChapter;

    @Column(name = "related_characters")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> relatedCharacters;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNovelId() { return novelId; }
    public void setNovelId(String novelId) { this.novelId = novelId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTitleNormalized() { return titleNormalized; }
    public void setTitleNormalized(String titleNormalized) { this.titleNormalized = titleNormalized; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public PlotThreadStatus getStatus() { return status == null ? null : PlotThreadStatus.valueOf(status); }
    public void setStatus(PlotThreadStatus status) { this.status = status == null ? null : status.name(); }
    public Integer getFirstSeenChapter() { return firstSeenChapter; }
    public void setFirstSeenChapter(Integer firstSeenChapter) { this.firstSeenChapter = firstSeenChapter; }
    public Integer getLastSeenChapter() { return lastSeenChapter; }
    public void setLastSeenChapter(Integer lastSeenChapter) { this.lastSeenChapter = lastSeenChapter; }
    public List<String> getRelatedCharacters() { return relatedCharacters; }
    public void setRelatedCharacters(List<String> relatedCharacters) { this.relatedCharacters = relatedCharacters; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
