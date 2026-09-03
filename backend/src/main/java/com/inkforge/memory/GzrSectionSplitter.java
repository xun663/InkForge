package com.inkforge.memory;

import com.inkforge.chapter.Chapter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蛊真人 EPUB 精排正文以「第…节」分节（号在卷间重置）。与
 * {@code GzrFullMemoryCostRun} 的切分规则保持一致，保证 outcomes/chapter-k.json
 * 的 k 与 {@link Chapter#ordinal()} 对齐。不走 {@code ChapterSplitter}：后者会把
 * 目录/制作说明收成「前言」，导致与 2335 节 outcomes 错位。
 */
public final class GzrSectionSplitter {

    private static final Pattern SEC = Pattern.compile("^第[零〇一二两三四五六七八九十百千0-9]+节[:：]?(.*)$");

    private GzrSectionSplitter() {
    }

    /** 跳过正文前的制作说明/目录，按「节」切分为 Chapter(ordinal=k, chapterNo=k+1)。 */
    public static List<Chapter> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (SEC.matcher(lines[i].strip()).matches()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalArgumentException("未找到正文分节起点（第…节）");
        }
        List<Chapter> chapters = new ArrayList<>();
        StringBuilder body = null;
        String title = null;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].strip();
            Matcher matcher = SEC.matcher(line);
            if (matcher.matches()) {
                if (body != null) {
                    chapters.add(chapter(chapters.size(), title, body.toString().trim()));
                }
                title = matcher.group(1) == null ? "" : matcher.group(1).strip();
                body = new StringBuilder();
            } else if (body != null && !line.isEmpty()) {
                body.append(line).append('\n');
            }
        }
        if (body != null) {
            chapters.add(chapter(chapters.size(), title, body.toString().trim()));
        }
        return chapters;
    }

    private static Chapter chapter(int ordinal, String title, String content) {
        return new Chapter(ordinal, ordinal + 1, title, content);
    }
}
