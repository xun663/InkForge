package com.inkforge.infrastructure.persistence;

import com.inkforge.chapter.Chapter;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.planning.ContinuationMode;
import com.inkforge.planning.EndingAnalysis;
import com.inkforge.planning.PlanStatus;
import com.inkforge.planning.PlanStep;
import com.inkforge.planning.PlotThread;
import com.inkforge.planning.PlotThreadStatus;
import com.inkforge.planning.StoryPlan;
import com.inkforge.planning.StoryPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6 planning layer PostgreSQL integration（V5 迁移 + 双仓储）。
 * WITHOUT Docker these are SKIPPED automatically (disabledWithoutDocker)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresPlanningIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Autowired
    private StoryPlanRepository storyPlanRepository;

    @Autowired
    private com.inkforge.planning.PlotThreadRepository plotThreadRepository;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private DataSource dataSource;

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void flywayV5CreatedPlanningTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : List.of("story_plan", "plot_thread")) {
                ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '" + table + "'");
                rs.next();
                assertThat(rs.getInt(1)).as("表 %s 应存在", table).isEqualTo(1);
            }
            ResultSet columns = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name = 'generation_log' AND column_name IN ('mode','plan_id')");
            columns.next();
            assertThat(columns.getInt(1)).as("generation_log 应新增 mode/plan_id 两列").isEqualTo(2);
        }
    }

    @Test
    void storyPlanRoundTripThroughPostgres() {
        String novelId = saveNovel("it-novel-plan");
        StoryPlan plan = new StoryPlan("it-plan-1", novelId, ContinuationMode.ENDING,
                "以终局战作结", "主线决战", "最终冲突", "结局方向",
                List.of(new PlanStep(0, "阶段一", "解伏笔", "收束"),
                        new PlanStep(1, "阶段二", "终局", "收束主线")),
                List.of("林默"), List.of("黑玉佩来源"), List.of(),
                "用户要求", new EndingAnalysis("主线", List.of(), List.of("伏笔A"),
                        "世界状态", List.of(), "最终冲突", "结局方向", List.of()),
                PlanStatus.DRAFT, NOW, NOW);

        storyPlanRepository.save(plan);

        assertThat(storyPlanRepository.findById("it-plan-1")).contains(plan);
        assertThat(storyPlanRepository.findByNovelId(novelId)).containsExactly(plan);
    }

    @Test
    void plotThreadUpsertsByNormalizedTitle() {
        String novelId = saveNovel("it-novel-thread");
        plotThreadRepository.save(new PlotThread("it-thread-1", novelId, "黑玉佩来源",
                "首次发现", PlotThreadStatus.OPEN, 86, 100, List.of("方源"), NOW, NOW));

        // 空白差异不产生第二条（normalized 匹配）
        assertThat(plotThreadRepository.findByTitle(novelId, PlotThread.normalized("黑玉佩 来源")))
                .isPresent();
        assertThat(plotThreadRepository.findByNovelId(novelId)).hasSize(1);
        assertThat(plotThreadRepository.findOpenByNovelId(novelId)).hasSize(1);
    }

    @Test
    void secondActivePlanForSameNovelViolatesPartialUniqueIndex() {
        String novelId = saveNovel("it-novel-active");
        storyPlanRepository.save(new StoryPlan("it-active-1", novelId, ContinuationMode.PLOT_CHOICE,
                "计划一", "s", "g", "", List.of(new PlanStep(0, "t", "s", "g")),
                List.of(), List.of(), List.of(), null, null, PlanStatus.CONFIRMED, NOW, NOW));

        StoryPlan second = new StoryPlan("it-active-2", novelId, ContinuationMode.EXPANSION,
                "计划二", "s", "g", "", List.of(new PlanStep(0, "t", "s", "g")),
                List.of(), List.of(), List.of(), null, null, PlanStatus.DRAFT, NOW, NOW);

        // DB 层兜底：同一小说第二个活跃计划违反 partial unique index
        assertThatThrownBy(() -> storyPlanRepository.save(second))
                .isInstanceOf(Exception.class);
        assertThat(storyPlanRepository.findById("it-active-2")).isEmpty();
    }

    private String saveNovel(String prefix) {
        String novelId = prefix + "-" + System.nanoTime();
        novelRepository.save(new Novel(novelId, "IT 小说", "it.txt", List.of(
                new Chapter(0, 1, "第一章", "正文。"))));
        return novelId;
    }
}
