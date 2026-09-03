package com.inkforge.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** JPA persistence entity for chapters. */
@Entity
@Table(name = "chapter")
public class ChapterEntity {

    @EmbeddedId
    private NovelChapterId id;

    @Column(name = "chapter_no")
    private Integer chapterNo;

    @Column(name = "title")
    private String title;

    @Column(name = "content")
    private String content;

    @Column(name = "char_count")
    private int charCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", insertable = false, updatable = false)
    private NovelEntity novel;

    public NovelChapterId getId() {
        return id;
    }

    public void setId(NovelChapterId id) {
        this.id = id;
    }

    public Integer getChapterNo() {
        return chapterNo;
    }

    public void setChapterNo(Integer chapterNo) {
        this.chapterNo = chapterNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getCharCount() {
        return charCount;
    }

    public void setCharCount(int charCount) {
        this.charCount = charCount;
    }

    public NovelEntity getNovel() {
        return novel;
    }

    public void setNovel(NovelEntity novel) {
        this.novel = novel;
    }
}
