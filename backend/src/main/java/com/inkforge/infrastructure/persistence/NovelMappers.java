package com.inkforge.infrastructure.persistence;

import com.inkforge.chapter.Chapter;
import com.inkforge.infrastructure.persistence.entity.ChapterEntity;
import com.inkforge.infrastructure.persistence.entity.NovelChapterId;
import com.inkforge.infrastructure.persistence.entity.NovelEntity;
import com.inkforge.novel.Novel;

import java.time.Instant;
import java.util.List;

/** Domain record ↔ JPA entity mapping for novels and chapters. */
public final class NovelMappers {

    private NovelMappers() {
    }

    public static NovelEntity toEntity(Novel novel) {
        NovelEntity entity = new NovelEntity();
        entity.setId(novel.id());
        entity.setTitle(novel.title());
        entity.setSourceFileName(novel.sourceFileName());
        entity.setChapterCount(novel.chapterCount());
        entity.setCreatedAt(Instant.now());
        List<ChapterEntity> chapters = novel.chapters().stream()
                .map(NovelMappers::toEntity)
                .toList();
        entity.setChapters(chapters);
        for (ChapterEntity chapter : chapters) {
            chapter.setNovel(entity);
            // the composite key carries the FK column — keep it in sync for inserts
            chapter.getId().setNovelId(novel.id());
        }
        return entity;
    }

    public static ChapterEntity toEntity(Chapter chapter) {
        ChapterEntity entity = new ChapterEntity();
        entity.setId(new NovelChapterId(null, chapter.ordinal()));
        entity.setChapterNo(chapter.chapterNo());
        entity.setTitle(chapter.title());
        entity.setContent(chapter.content());
        entity.setCharCount(chapter.charCount());
        return entity;
    }

    public static Novel toDomain(NovelEntity entity) {
        List<Chapter> chapters = entity.getChapters().stream()
                .map(NovelMappers::toDomain)
                .toList();
        return new Novel(entity.getId(), entity.getTitle(), entity.getSourceFileName(), chapters);
    }

    public static Chapter toDomain(ChapterEntity entity) {
        return new Chapter(
                entity.getId().getOrdinal(), entity.getChapterNo(),
                entity.getTitle(), entity.getContent());
    }
}
