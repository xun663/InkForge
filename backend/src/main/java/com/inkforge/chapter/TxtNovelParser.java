package com.inkforge.chapter;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Parses a raw TXT novel into chapters: charset detection → rule-based chapter
 * splitting. Purely deterministic — no LLM involved.
 */
@Component
public class TxtNovelParser {

    private final CharsetDetector charsetDetector;
    private final ChapterSplitter chapterSplitter;

    public TxtNovelParser(CharsetDetector charsetDetector, ChapterSplitter chapterSplitter) {
        this.charsetDetector = charsetDetector;
        this.chapterSplitter = chapterSplitter;
    }

    public ParsedNovel parse(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String text = charsetDetector.decode(bytes);
        if (text.isBlank()) {
            throw new IllegalArgumentException("文件中没有可识别的文本内容");
        }
        // a text without chapter markers is accepted as a single 全文 chapter
        List<Chapter> chapters = chapterSplitter.split(text);
        return new ParsedNovel(stripExtension(fileName), chapters);
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名小说";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
