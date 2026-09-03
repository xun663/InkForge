package com.inkforge.novel;

import com.inkforge.chapter.Chapter;

import java.util.List;

/** A parsed novel with its chapters. */
public record Novel(String id, String title, String sourceFileName, List<Chapter> chapters) {

    public Novel {
        chapters = List.copyOf(chapters);
    }

    public int chapterCount() {
        return chapters.size();
    }

    public Chapter lastChapter() {
        if (chapters.isEmpty()) {
            throw new IllegalStateException("Novel has no chapters");
        }
        return chapters.get(chapters.size() - 1);
    }
}
