package com.inkforge.retrieval;

import com.inkforge.chapter.Chapter;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.SummaryCharacter;
import com.inkforge.novel.Novel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalQueryBuilderTest {

    private StoryMemoryRepository memoryRepository;
    private RetrievalQueryBuilder builder;

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @BeforeEach
    void setUp() {
        memoryRepository = new InMemoryStoryMemoryRepository();
        builder = new RetrievalQueryBuilder(memoryRepository);
    }

    private Novel novelWith(String tail) {
        return new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "正文一。"),
                new Chapter(1, 2, "血魔现世", tail)));
    }

    @Test
    void buildsThreeQueriesInFixedOrder() {
        memoryRepository.saveSummary(new ChapterSummary("n1", 1,
                "林默与血魔对峙，右手受伤，血魔逃离。", List.of("对峙"),
                List.of(new SummaryCharacter("林默", "主角"), new SummaryCharacter("血魔", "反派")),
                List.of("后山"), List.of(), List.of("血魔的行踪", "伤势未愈"), NOW));

        List<RetrievalQuery> queries = builder.build(novelWith("林默缓缓睁开双眼。"));

        assertThat(queries).hasSize(3);
        assertThat(queries.get(0).type()).isEqualTo("primary");
        assertThat(queries.get(0).text()).contains("林默缓缓睁开双眼").contains("林默与血魔对峙");
        assertThat(queries.get(1).type()).isEqualTo("character");
        assertThat(queries.get(1).text()).contains("林默", "血魔");
        assertThat(queries.get(2).type()).isEqualTo("thread");
        assertThat(queries.get(2).text()).contains("血魔的行踪");
    }

    @Test
    void withoutSummaryOnlyPrimaryIsGenerated() {
        List<RetrievalQuery> queries = builder.build(novelWith("林默缓缓睁开双眼。"));

        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().type()).isEqualTo("primary");
        assertThat(queries.getFirst().text()).contains("林默缓缓睁开双眼");
    }

    @Test
    void blankTailNeverEmitsEmptyQuery() {
        List<RetrievalQuery> queries = builder.build(novelWith("   "));

        assertThat(queries).isEmpty();
    }

    @Test
    void summaryWithoutThreadsSkipsThreadQuery() {
        memoryRepository.saveSummary(new ChapterSummary("n1", 1,
                "摘要", List.of(), List.of(), List.of(), List.of(), List.of(), NOW));

        List<RetrievalQuery> queries = builder.build(novelWith("林默缓缓睁开双眼。"));

        assertThat(queries).extracting(RetrievalQuery::type)
                .containsExactly("primary");
    }

    @Test
    void deterministicAcrossCalls() {
        memoryRepository.saveSummary(new ChapterSummary("n1", 1,
                "林默与血魔对峙。", List.of(),
                List.of(new SummaryCharacter("林默", "主角")),
                List.of(), List.of(), List.of("线索一"), NOW));

        List<RetrievalQuery> first = builder.build(novelWith("尾部文本。"));
        List<RetrievalQuery> second = builder.build(novelWith("尾部文本。"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void tailIsCappedForVeryLongChapters() {
        String longTail = "字".repeat(500);
        List<RetrievalQuery> queries = builder.build(novelWith(longTail));

        assertThat(queries.getFirst().text().length()).isLessThanOrEqualTo(301); // 300 cap
    }
}
