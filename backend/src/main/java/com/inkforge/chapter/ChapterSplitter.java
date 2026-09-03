package com.inkforge.chapter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based chapter splitter for Chinese web novels. Deterministic; never calls an LLM.
 *
 * <p>Supported chapter markers (line-based, optional leading whitespace):
 * <ul>
 *   <li>{@code 第1章 / 第001章 / 第 1 章 / 第一章 / 第十二章 / 第两百零五章}
 *       with 章/节/回/集/部</li>
 *   <li>{@code 序章 / 楔子 / 引子 / 序言 / 尾声 / 终章 / 大结局 / 番外(篇/一/1…)}</li>
 * </ul>
 * Volume titles ({@code 第一卷}, {@code 卷三} …) are NOT chapters — their line is kept
 * inside the current chapter text. Text before the first chapter marker becomes a
 * preamble chapter titled 前言 (when non-blank). A text with no chapter markers at all
 * becomes a single chapter titled 全文.
 */
@Component
public class ChapterSplitter {

    private static final Pattern CHAPTER_MARKER = Pattern.compile(
            "^\\s*第\\s*([0-9]+|[零〇一二两三四五六七八九十百千万]+)\\s*[章节回集部]\\s*(?:[:：、\\-— ]\\s*)?(.*)$");
    private static final Pattern VOLUME_MARKER = Pattern.compile(
            "^\\s*第\\s*(?:[0-9]+|[零〇一二两三四五六七八九十百千万]+)\\s*卷.*$");
    private static final Pattern SPECIAL_MARKER = Pattern.compile(
            "^\\s*(序章|楔子|引子|序言|前言|尾声|终章|大结局|番外篇|番外(?:\\s*[零〇一二两三四五六七八九十百千万0-9]+)?)\\s*(?:[:：、\\-— ]\\s*)?(.*)$");

    public List<Chapter> split(String text) {
        if (text == null) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        List<Chapter> chapters = new ArrayList<>();
        List<String> pendingContent = new ArrayList<>();
        String pendingTitle = null;
        Integer pendingChapterNo = null;
        boolean markerSeen = false;

        for (String rawLine : lines) {
            String line = rawLine.strip();
            Matcher chapterMatcher = CHAPTER_MARKER.matcher(line);
            Matcher specialMatcher = SPECIAL_MARKER.matcher(line);
            if (chapterMatcher.matches() && !VOLUME_MARKER.matcher(line).matches()) {
                // this flush is triggered by a marker, so any pending preamble is a 前言
                flush(chapters, pendingTitle, pendingChapterNo, pendingContent, true);
                markerSeen = true;
                pendingChapterNo = parseChapterNo(chapterMatcher.group(1));
                pendingTitle = chapterMatcher.group(2);
                pendingContent = new ArrayList<>();
            } else if (specialMatcher.matches()) {
                flush(chapters, pendingTitle, pendingChapterNo, pendingContent, true);
                markerSeen = true;
                pendingChapterNo = null; // 序章/楔子/番外 carry no numeric number
                pendingTitle = line;
                pendingContent = new ArrayList<>();
            } else {
                // regular text, blank line, or a volume title — kept inside the current chapter
                pendingContent.add(line);
            }
        }
        flush(chapters, pendingTitle, pendingChapterNo, pendingContent, markerSeen);
        return chapters;
    }

    private static void flush(List<Chapter> chapters, String title, Integer chapterNo,
                              List<String> content, boolean markerSeen) {
        boolean nothingBeforeFirstMarker = title == null && content.stream().allMatch(String::isBlank);
        if (nothingBeforeFirstMarker) {
            return;
        }
        // 前言 = preamble before real chapters; 全文 = a text with no chapter markers at all
        String effectiveTitle = title == null ? (markerSeen ? "前言" : "全文") : title;
        chapters.add(new Chapter(chapters.size(), chapterNo, effectiveTitle, joinNonEmpty(content)));
    }

    private static String joinNonEmpty(List<String> content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content) {
            if (line.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static Integer parseChapterNo(String raw) {
        if (raw.matches("[0-9]+")) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int value = ChineseNumeral.parse(raw);
        return value > 0 ? value : null;
    }
}
