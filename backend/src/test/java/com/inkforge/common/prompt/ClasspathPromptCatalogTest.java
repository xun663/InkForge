package com.inkforge.common.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClasspathPromptCatalogTest {

    private final PromptCatalog catalog = new ClasspathPromptCatalog();

    @Test
    void rendersTemplateWithAllPlaceholdersResolved() {
        String rendered = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", "测试小说",
                "chapterNo", "第327章",
                "chapterTitle", "天剑宗后山"));

        assertThat(rendered)
                .contains("测试小说")
                .contains("第327章")
                .contains("天剑宗后山")
                .doesNotContain("{{");
    }

    @Test
    void failsFastOnUnresolvedPlaceholder() {
        assertThatThrownBy(() -> catalog.render("continuation.system.txt", Map.of("novelTitle", "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chapterNo");
    }

    @Test
    void failsFastOnUnknownTemplate() {
        assertThatThrownBy(() -> catalog.render("does-not-exist.txt", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist.txt");
    }
}
