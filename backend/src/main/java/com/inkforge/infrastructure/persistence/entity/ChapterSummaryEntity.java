package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.memory.SummaryCharacter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** JPA persistence entity for chapter summaries; list fields stored as JSONB via converters. */
@Entity
@Table(name = "chapter_summary")
public class ChapterSummaryEntity {

    @EmbeddedId
    private NovelChapterId id;

    @Column(name = "summary")
    private String summary;

    @Column(name = "key_events")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> keyEvents;

    @Column(name = "characters")
    @Convert(converter = JsonbConverters.SummaryCharacterListConverter.class)
    private List<SummaryCharacter> characters;

    @Column(name = "locations")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> locations;

    @Column(name = "important_items")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> importantItems;

    @Column(name = "unresolved_threads")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> unresolvedThreads;

    @Column(name = "created_at")
    private Instant createdAt;

    public NovelChapterId getId() {
        return id;
    }

    public void setId(NovelChapterId id) {
        this.id = id;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getKeyEvents() {
        return keyEvents;
    }

    public void setKeyEvents(List<String> keyEvents) {
        this.keyEvents = keyEvents;
    }

    public List<SummaryCharacter> getCharacters() {
        return characters;
    }

    public void setCharacters(List<SummaryCharacter> characters) {
        this.characters = characters;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations;
    }

    public List<String> getImportantItems() {
        return importantItems;
    }

    public void setImportantItems(List<String> importantItems) {
        this.importantItems = importantItems;
    }

    public List<String> getUnresolvedThreads() {
        return unresolvedThreads;
    }

    public void setUnresolvedThreads(List<String> unresolvedThreads) {
        this.unresolvedThreads = unresolvedThreads;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
