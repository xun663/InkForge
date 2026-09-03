package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.memory.FactCategory;
import com.inkforge.memory.FactStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA persistence entity for character facts (id-assigned → merge is an upsert, matching P2 semantics). */
@Entity
@Table(name = "character_fact")
public class CharacterFactEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "character_id")
    private String characterId;

    @Column(name = "category")
    @Enumerated(EnumType.STRING)
    private FactCategory category;

    @Column(name = "attribute")
    private String attribute;

    @Column(name = "value")
    private String value;

    @Column(name = "target_character")
    private String targetCharacter;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private FactStatus status;

    @Column(name = "valid_from")
    private int validFromChapter;

    @Column(name = "valid_until")
    private Integer validUntilChapter;

    @Column(name = "confidence")
    private double confidence;

    @Column(name = "source_chapter")
    private int sourceChapter;

    @Column(name = "source_quote")
    private String sourceQuote;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCharacterId() {
        return characterId;
    }

    public void setCharacterId(String characterId) {
        this.characterId = characterId;
    }

    public FactCategory getCategory() {
        return category;
    }

    public void setCategory(FactCategory category) {
        this.category = category;
    }

    public String getAttribute() {
        return attribute;
    }

    public void setAttribute(String attribute) {
        this.attribute = attribute;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getTargetCharacter() {
        return targetCharacter;
    }

    public void setTargetCharacter(String targetCharacter) {
        this.targetCharacter = targetCharacter;
    }

    public FactStatus getStatus() {
        return status;
    }

    public void setStatus(FactStatus status) {
        this.status = status;
    }

    public int getValidFromChapter() {
        return validFromChapter;
    }

    public void setValidFromChapter(int validFromChapter) {
        this.validFromChapter = validFromChapter;
    }

    public Integer getValidUntilChapter() {
        return validUntilChapter;
    }

    public void setValidUntilChapter(Integer validUntilChapter) {
        this.validUntilChapter = validUntilChapter;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public int getSourceChapter() {
        return sourceChapter;
    }

    public void setSourceChapter(int sourceChapter) {
        this.sourceChapter = sourceChapter;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
