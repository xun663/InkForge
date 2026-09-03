package com.inkforge.chapter;

import java.util.List;

/** Result of parsing an uploaded TXT file: a title and its chapters. */
public record ParsedNovel(String title, List<Chapter> chapters) {

    public ParsedNovel {
        chapters = List.copyOf(chapters);
    }
}
