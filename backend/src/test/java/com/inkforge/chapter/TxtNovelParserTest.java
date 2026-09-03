package com.inkforge.chapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TxtNovelParserTest {

    private final TxtNovelParser parser = new TxtNovelParser(new CharsetDetector(), new ChapterSplitter());

    @Test
    void parsesUtf8Novel() throws IOException {
        ParsedNovel novel = parser.parse(Fixtures.bytes("utf8_standard.txt"), "utf8_standard.txt");

        assertThat(novel.title()).isEqualTo("utf8_standard");
        assertThat(novel.chapters()).hasSize(6);
        assertThat(novel.chapters().get(3).content()).contains("玄霜");
    }

    @Test
    void parsesGbkNovel() throws IOException {
        ParsedNovel novel = parser.parse(Fixtures.bytes("gbk_sample.txt"), "gbk_sample.txt");

        assertThat(novel.title()).isEqualTo("gbk_sample");
        assertThat(novel.chapters()).hasSize(2);
        // distinctive GBK-decoded content proves the bytes were decoded, not mojibake
        assertThat(novel.chapters().get(0).title()).isEqualTo("玄霜剑");
        assertThat(novel.chapters().get(0).content()).contains("认主");
        assertThat(novel.chapters().get(1).title()).isEqualTo("后山遇袭");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> parser.parse(new byte[0], "empty.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空");
    }

    @Test
    void textWithoutChapterMarkersBecomesSingleChapter() throws IOException {
        ParsedNovel novel = parser.parse(Fixtures.bytes("utf8_no_chapters.txt"), "plain.txt");

        assertThat(novel.chapters()).hasSize(1);
        assertThat(novel.chapters().get(0).title()).isEqualTo("全文");
        assertThat(novel.chapters().get(0).chapterNo()).isNull();
        assertThat(novel.chapters().get(0).content()).contains("没有章节标记");
    }

    @Test
    void stripsExtensionFromTitle() {
        ParsedNovel novel = parser.parse("第一章 标题\n正文。".getBytes(StandardCharsets.UTF_8), "剑道独尊.txt");
        assertThat(novel.title()).isEqualTo("剑道独尊");
    }
}
