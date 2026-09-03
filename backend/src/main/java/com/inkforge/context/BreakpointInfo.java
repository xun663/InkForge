package com.inkforge.context;

/**
 * Where the story currently ends.
 *
 * @param chapterOrdinal 0-based position of the last chapter in the document
 * @param chapterNo      numeric chapter number, or null for 楔子/番外 style chapters
 * @param chapterTitle   title of the last chapter
 * @param tailExcerpt    the final characters of the last chapter (the actual breakpoint text)
 */
public record BreakpointInfo(int chapterOrdinal, Integer chapterNo, String chapterTitle, String tailExcerpt) {
}
