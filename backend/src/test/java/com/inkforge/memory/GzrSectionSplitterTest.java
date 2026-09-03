package com.inkforge.memory;

import com.inkforge.chapter.Chapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GzrSectionSplitterTest {

    @Test
    void skipsPreambleAndSplitsByJie() {
        String text = """
                制作说明
                目录 第一节 开窍
                第一节：开窍
                方源睁开眼睛。
                第二节 重生
                确认春秋蝉有效。
                """;
        List<Chapter> chapters = GzrSectionSplitter.split(text);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).ordinal()).isZero();
        assertThat(chapters.get(0).chapterNo()).isEqualTo(1);
        assertThat(chapters.get(0).title()).isEqualTo("开窍");
        assertThat(chapters.get(0).content()).contains("方源睁开眼睛");
        assertThat(chapters.get(1).ordinal()).isEqualTo(1);
        assertThat(chapters.get(1).chapterNo()).isEqualTo(2);
        assertThat(chapters.get(1).title()).isEqualTo("重生");
        assertThat(chapters.get(1).content()).contains("春秋蝉");
    }

    @Test
    void rejectsTextWithoutJieMarkers() {
        assertThatThrownBy(() -> GzrSectionSplitter.split("只有前言没有节"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到正文分节起点");
    }
}
