package com.inkforge.chapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers real Chinese web novel structures: 第1章 / 第001章 / 第 1 章 / 第一章 /
 * 序章 / 楔子 / 番外 / 卷标题 / 前言 preamble / CRLF line endings.
 */
class ChapterSplitterTest {

    private final ChapterSplitter splitter = new ChapterSplitter();

    @Test
    void arabicNumerals() {
        List<Chapter> chapters = splitter.split("""
                第一章 开头
                正文一。
                第2章 后续
                正文二。
                """);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).chapterNo()).isEqualTo(1);
        assertThat(chapters.get(0).title()).isEqualTo("开头");
        assertThat(chapters.get(1).chapterNo()).isEqualTo(2);
    }

    @Test
    void leadingZerosAndSpaces() {
        List<Chapter> chapters = splitter.split("""
                第 1 章 山门
                正文。
                第 002 章 外门
                正文。
                第 003 章 灵石
                正文。
                """);
        assertThat(chapters).hasSize(3);
        assertThat(chapters.stream().map(Chapter::chapterNo)).containsExactly(1, 2, 3);
        assertThat(chapters.get(1).title()).isEqualTo("外门");
    }

    @Test
    void chineseNumerals() {
        List<Chapter> chapters = splitter.split("""
                第一章 初入宗门
                正文。
                第十二章 大比
                正文。
                第一百零三章 血战之后
                正文。
                """);
        assertThat(chapters.stream().map(Chapter::chapterNo)).containsExactly(1, 12, 103);
    }

    @Test
    void specialMarkersHaveNoChapterNumber() {
        List<Chapter> chapters = splitter.split("""
                楔子
                剑断之日。
                序章
                山门之前。
                番外一 旧事
                多年以后。
                """);
        assertThat(chapters).hasSize(3);
        assertThat(chapters.stream().map(Chapter::chapterNo)).containsOnlyNulls();
        assertThat(chapters.stream().map(Chapter::title)).containsExactly("楔子", "序章", "番外一 旧事");
    }

    @Test
    void volumeTitlesAreNotChaptersAndStayInText() {
        List<Chapter> chapters = splitter.split("""
                第一章 拜入山门
                正文一。
                第一卷 风起天剑
                第二章 玄霜剑
                正文二。
                """);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).content()).contains("第一卷 风起天剑");
        assertThat(chapters.get(0).title()).isEqualTo("拜入山门");
    }

    @Test
    void preambleBecomes前言Chapter() {
        List<Chapter> chapters = splitter.split("""
                天剑宗立派三千载，坐镇大荒北境。
                第一章 拜入山门
                正文。
                """);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).ordinal()).isZero();
        assertThat(chapters.get(0).chapterNo()).isNull();
        assertThat(chapters.get(0).title()).isEqualTo("前言");
        assertThat(chapters.get(0).content()).contains("天剑宗立派三千载");
    }

    @Test
    void crlfLineEndingsAreNormalized() {
        List<Chapter> chapters = splitter.split("第一章 开头\r\n正文一。\r\n\r\n第二章 后续\r\n正文二。\r\n");
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).content()).doesNotContain("\r");
        assertThat(chapters.get(1).content()).isEqualTo("正文二。");
    }

    @Test
    void ordinalsAreStableAcrossMixedMarkers() {
        List<Chapter> chapters = splitter.split("""
                楔子
                正文。
                第一章 一
                正文。
                番外 尾
                正文。
                """);
        assertThat(chapters.stream().map(Chapter::ordinal)).containsExactly(0, 1, 2);
        assertThat(chapters.get(1).chapterNo()).isEqualTo(1);
    }

    @Test
    void textWithoutAnyMarkerBecomesSingle全文Chapter() {
        List<Chapter> chapters = splitter.split("这是一个没有章节标记的普通文本。\n只有一些说明文字。");

        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).title()).isEqualTo("全文");
        assertThat(chapters.get(0).chapterNo()).isNull();
        assertThat(chapters.get(0).content()).contains("普通文本");
    }

    @Test
    void emptyTextYieldsNoChapters() {
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split(null)).isEmpty();
    }

    @Test
    void realWorldFixtureStructure() throws IOException {
        List<Chapter> chapters = splitter.split(Fixtures.text("utf8_standard.txt"));

        assertThat(chapters).hasSize(6);
        assertThat(chapters.get(0).title()).isEqualTo("前言");
        assertThat(chapters.get(1).title()).isEqualTo("楔子");
        assertThat(chapters.get(2).chapterNo()).isEqualTo(1);
        assertThat(chapters.get(2).title()).isEqualTo("拜入山门");
        assertThat(chapters.get(3).chapterNo()).isEqualTo(2);
        assertThat(chapters.get(3).title()).isEqualTo("玄霜剑");
        assertThat(chapters.get(4).chapterNo()).isEqualTo(3);
        assertThat(chapters.get(5).title()).startsWith("番外");
        assertThat(chapters.get(5).chapterNo()).isNull();
        // volume line kept inside the 楔子 chapter
        assertThat(chapters.get(1).content()).contains("第一卷 风起天剑");
    }
}
