package com.inkforge.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** JPA persistence entity for story events. */
@Entity
@Table(name = "story_event")
public class StoryEventEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "novel_id")
    private String novelId;

    @Column(name = "chapter_ordinal")
    private int chapterOrdinal;

    @Column(name = "title")
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "participants")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> participants;

    @Column(name = "location")
    private String location;

    @Column(name = "consequences")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> consequences;

    @Column(name = "importance")
    private int importance;

    @Column(name = "source_quote")
    private String sourceQuote;

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

    public int getChapterOrdinal() {
        return chapterOrdinal;
    }

    public void setChapterOrdinal(int chapterOrdinal) {
        this.chapterOrdinal = chapterOrdinal;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getParticipants() {
        return participants;
    }

    public void setParticipants(List<String> participants) {
        this.participants = participants;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public List<String> getConsequences() {
        return consequences;
    }

    public void setConsequences(List<String> consequences) {
        this.consequences = consequences;
    }

    public int getImportance() {
        return importance;
    }

    public void setImportance(int importance) {
        this.importance = importance;
    }

    public String getSourceQuote() {
        return sourceQuote;
    }

    public void setSourceQuote(String sourceQuote) {
        this.sourceQuote = sourceQuote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
