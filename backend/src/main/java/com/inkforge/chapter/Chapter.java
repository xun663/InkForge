package com.inkforge.chapter;

/**
 * One chapter of a novel.
 *
 * @param ordinal    position in the document (0-based); the stable ordering key —
 *                   handles 楔子/番外 which carry no numeric chapter number
 * @param chapterNo  parsed chapter number when the title matched a numeric pattern, else null
 * @param title      chapter title (marker + suffix), may be blank
 * @param content    raw chapter text
 */
public record Chapter(int ordinal, Integer chapterNo, String title, String content) {

    public int charCount() {
        return content.length();
    }
}
