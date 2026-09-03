package com.inkforge.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** Composite key (novelId, ordinal) shared by chapter, chapter_summary, memory_extraction_record. */
@Embeddable
public class NovelChapterId implements Serializable {

    @Column(name = "novel_id", nullable = false)
    private String novelId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    public NovelChapterId() {
    }

    public NovelChapterId(String novelId, int ordinal) {
        this.novelId = novelId;
        this.ordinal = ordinal;
    }

    public String getNovelId() {
        return novelId;
    }

    public void setNovelId(String novelId) {
        this.novelId = novelId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NovelChapterId other)) {
            return false;
        }
        return ordinal == other.ordinal && Objects.equals(novelId, other.novelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(novelId, ordinal);
    }
}
