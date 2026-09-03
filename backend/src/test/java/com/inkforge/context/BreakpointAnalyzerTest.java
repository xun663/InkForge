package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.novel.Novel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BreakpointAnalyzerTest {

    private final BreakpointAnalyzer analyzer =
            new BreakpointAnalyzer(new ContextProperties(8192, 100, java.util.Map.of()));

    @Test
    void reportsLastChapterAndFullTailForShortChapter() {
        Novel novel = new Novel("n1", "测试", "t.txt", List.of(
                new Chapter(0, 1, "第一章", "短内容。")));

        BreakpointInfo info = analyzer.analyze(novel);

        assertThat(info.chapterOrdinal()).isZero();
        assertThat(info.chapterNo()).isEqualTo(1);
        assertThat(info.chapterTitle()).isEqualTo("第一章");
        assertThat(info.tailExcerpt()).isEqualTo("短内容。");
    }

    @Test
    void truncatesLongTailToConfiguredLength() {
        String body = "字".repeat(500);
        Novel novel = new Novel("n1", "测试", "t.txt", List.of(
                new Chapter(0, 327, "天剑宗后山", body)));

        BreakpointInfo info = analyzer.analyze(novel);

        assertThat(info.tailExcerpt()).startsWith("……");
        assertThat(info.tailExcerpt()).hasSize(102); // 2 (……) + 100 tail chars
        assertThat(info.tailExcerpt()).endsWith(body.substring(body.length() - 100));
    }

    @Test
    void handlesSpecialChaptersWithNoNumber() {
        Novel novel = new Novel("n1", "测试", "t.txt", List.of(
                new Chapter(0, null, "楔子", "剑断之日。"),
                new Chapter(1, null, "番外 山门旧事", "多年以后。")));

        BreakpointInfo info = analyzer.analyze(novel);

        assertThat(info.chapterOrdinal()).isEqualTo(1);
        assertThat(info.chapterNo()).isNull();
        assertThat(info.chapterTitle()).isEqualTo("番外 山门旧事");
    }
}
