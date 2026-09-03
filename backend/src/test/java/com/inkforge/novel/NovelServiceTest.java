package com.inkforge.novel;

import com.inkforge.chapter.CharsetDetector;
import com.inkforge.chapter.ChapterSplitter;
import com.inkforge.chapter.Fixtures;
import com.inkforge.chapter.TxtNovelParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Import-layer resource protection: the limits are defensive guards only and are fully
 * decoupled from the LLM extraction budget. A normal web-novel file must always pass.
 */
class NovelServiceTest {

    private InMemoryNovelRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNovelRepository();
    }

    private NovelService serviceWith(ImportProperties properties) {
        return new NovelService(repository,
                new TxtNovelParser(new CharsetDetector(), new ChapterSplitter()), properties);
    }

    @Test
    void normalNovelPassesAllLimits() throws IOException {
        Novel novel = serviceWith(new ImportProperties(104_857_600L, 10_000, 100_000))
                .ingest(Fixtures.bytes("utf8_standard.txt"), "utf8_standard.txt");

        assertThat(novel.chapterCount()).isEqualTo(6);
    }

    @Test
    void chapterCountOverLimitIsRejected() throws IOException {
        ImportProperties tiny = new ImportProperties(104_857_600L, 2, 100_000);

        assertThatThrownBy(() -> serviceWith(tiny)
                .ingest(Fixtures.bytes("utf8_standard.txt"), "utf8_standard.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("章节数");
    }

    @Test
    void oversizedChapterIsRejected() throws IOException {
        ImportProperties tiny = new ImportProperties(104_857_600L, 10_000, 10);

        assertThatThrownBy(() -> serviceWith(tiny)
                .ingest(Fixtures.bytes("utf8_standard.txt"), "utf8_standard.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("章节过长");
    }

    @Test
    void fileOverSizeLimitIsRejected() throws IOException {
        ImportProperties tiny = new ImportProperties(10L, 10_000, 100_000);

        assertThatThrownBy(() -> serviceWith(tiny)
                .ingest(Fixtures.bytes("utf8_standard.txt"), "utf8_standard.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件过大");
    }
}
