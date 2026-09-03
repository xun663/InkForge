package com.inkforge.planning;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6：规划输出解析器的两级校验 —— 结构性错误抛异常（触发 repair 重试），
 * 单条无效静默丢弃；步骤编号不可信、统一重排。
 */
class PlanOutputParserTest {

    private final PlanOutputParser parser = new PlanOutputParser(new ObjectMapper());

    @Test
    void parsesFencedDirectionArrayWrappedInProse() {
        String raw = """
                好的，以下是三个候选方向：
                ```json
                [
                  {"title":"调查青云城失踪事件","summary":"调查连续失踪案件。",
                   "rationale":"与断章悬念直接相关。","involvedCharacters":["林默"],
                   "relatedThreads":["失踪案主使"],"relatedWorldElements":["青云城"],
                   "possibleConflict":"可能触发与天魔宗的冲突。","newConflict":null,
                   "directionGoal":"揭开失踪案真相"},
                  {"title":"黑衣人再次现身","summary":"黑衣人重现身，目标玄霜剑。",
                   "rationale":"延续交战线索。","directionGoal":"查明来历"},
                  {"title":"开启青云秘境","summary":"后山遗迹异动。",
                   "rationale":"拓展未开发空间。","newConflict":"秘境传承之争。",
                   "directionGoal":"开启新地图"}
                ]
                ```
                以上。
                """;

        List<PlanDirection> directions = parser.parseDirections(raw);

        assertThat(directions).hasSize(3);
        assertThat(directions.get(0).title()).isEqualTo("调查青云城失踪事件");
        assertThat(directions.get(0).involvedCharacters()).containsExactly("林默");
        assertThat(directions.get(0).conflict()).isEqualTo("可能触发与天魔宗的冲突。");
        assertThat(directions.get(1).conflict()).isEmpty();
        assertThat(directions.get(2).conflict()).isEqualTo("秘境传承之争。");
        assertThat(directions.get(2).directionGoal()).isEqualTo("开启新地图");
    }

    @Test
    void parsesObjectWrappedDirections() {
        String raw = """
                {"directions":[
                  {"title":"方向一","summary":"摘要一","directionGoal":"目标"},
                  {"title":"方向二","summary":"摘要二","directionGoal":"目标"}
                ]}
                """;

        assertThat(parser.parseDirections(raw)).hasSize(2);
    }

    @Test
    void dropsInvalidItemsButKeepsValidOnes() {
        String raw = """
                [
                  {"title":"","summary":"无标题，丢弃"},
                  {"summary":"无内容，丢弃"},
                  {"title":"有效方向","summary":"完整有效","directionGoal":"目标"}
                ]
                """;

        List<PlanDirection> directions = parser.parseDirections(raw);

        assertThat(directions).hasSize(1);
        assertThat(directions.getFirst().title()).isEqualTo("有效方向");
    }

    @Test
    void failsWhenNoValidDirectionRemains() {
        String raw = """
                [{"title":"","summary":"x"},{"title":"y"}]
                """;

        assertThatThrownBy(() -> parser.parseDirections(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何有效的剧情方向");
    }

    @Test
    void failsOnNonJsonOutput() {
        assertThatThrownBy(() -> parser.parseDirections("抱歉，我无法完成该请求。"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到 JSON");

        assertThatThrownBy(() -> parser.parseDirections("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM 返回为空");
    }

    @Test
    void parsesEndingPlanAndReindexesStepsFromZero() {
        String raw = """
                ```json
                {
                  "mainArc":"魔门战争决战","characterArcs":[{"name":"林默","arc":"成长"},{"name":"","arc":"无效丢弃"}],
                  "foreshadowing":["剑穗来历"],"worldState":"大战在即",
                  "droppableSubplots":["采药支线"],"finalConflict":"林默对血魔",
                  "endingDirection":"终结血魔",
                  "threads":[
                    {"title":"血魔行踪成谜","summary":"败退后去向不明","resolution":"决战揭露","firstSeenChapter":7},
                    {"title":"","summary":"无效丢弃"}
                  ],
                  "steps":[
                    {"index":9,"title":"揭示剑穗","summary":"回溯恩怨","phaseGoal":"收束伏笔"},
                    {"title":"最终决战","summary":"决战","phaseGoal":"主线收束"}
                  ]
                }
                ```
                """;

        PlanOutputParser.EndingPlanParse parse = parser.parseEndingPlan(raw);

        assertThat(parse.analysis().mainArc()).isEqualTo("魔门战争决战");
        assertThat(parse.analysis().characterArcs()).hasSize(1);
        assertThat(parse.analysis().threads()).hasSize(1);
        assertThat(parse.analysis().threads().getFirst().firstSeenChapter()).isEqualTo(7);
        assertThat(parse.analysis().threads().getFirst().relatedCharacters()).isEmpty();
        // 编号重排：LLM 给的 index=9 不可信
        assertThat(parse.steps()).extracting(PlanStep::index).containsExactly(0, 1);
        assertThat(parse.steps().get(1).title()).isEqualTo("最终决战");
    }

    @Test
    void endingPlanWithoutStepsFailsStructurally() {
        String raw = """
                {"mainArc":"主线","steps":[{"title":"","summary":"无效"}]}
                """;

        assertThatThrownBy(() -> parser.parseEndingPlan(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少有效的收束步骤");
    }
}
