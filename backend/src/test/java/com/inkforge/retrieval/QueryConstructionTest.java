package com.inkforge.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P5-B1：意图分类（6 类）、确定性、≤3 上限、空/歧义输入不崩溃。 */
class QueryConstructionTest {

    private final QueryIntentClassifier classifier = new QueryIntentClassifier();
    private final QueryConstructionService service = new QueryConstructionService(classifier);

    @Test
    void recentPlotClassification() {
        assertThat(classifier.classify("主角当前的处境怎么样")).isEqualTo(QueryIntent.RECENT_PLOT);
        assertThat(classifier.classify("现在的剧情进行到哪了")).isEqualTo(QueryIntent.RECENT_PLOT);
    }

    @Test
    void characterClassification() {
        assertThat(classifier.classify("主角的实力状态如何")).isEqualTo(QueryIntent.CHARACTER);
        assertThat(classifier.classify("他的身份和经历")).isEqualTo(QueryIntent.CHARACTER);
    }

    @Test
    void relationshipClassification() {
        assertThat(classifier.classify("两人的关系如何形成")).isEqualTo(QueryIntent.RELATIONSHIP);
        assertThat(classifier.classify("他们之间的冲突")).isEqualTo(QueryIntent.RELATIONSHIP);
        assertThat(classifier.classify("他的师承与师徒关系")).isEqualTo(QueryIntent.RELATIONSHIP);
    }

    @Test
    void worldbuildingClassification() {
        assertThat(classifier.classify("苦海如何开辟和修炼")).isEqualTo(QueryIntent.WORLDBUILDING);
        assertThat(classifier.classify("修炼体系有哪些规则")).isEqualTo(QueryIntent.WORLDBUILDING);
        assertThat(classifier.classify("境界层次怎么划分")).isEqualTo(QueryIntent.WORLDBUILDING);
    }

    @Test
    void foreshadowingClassification() {
        assertThat(classifier.classify("前文埋下的伏笔")).isEqualTo(QueryIntent.FORESHADOWING);
        assertThat(classifier.classify("一直没有解释的异常线索")).isEqualTo(QueryIntent.FORESHADOWING);
    }

    @Test
    void historicalEventClassification() {
        assertThat(classifier.classify("这件大事的经过")).isEqualTo(QueryIntent.HISTORICAL_EVENT);
        assertThat(classifier.classify("事情的起因和发展")).isEqualTo(QueryIntent.HISTORICAL_EVENT);
    }

    @Test
    void relationshipWinsOverHistoricalWhenBothPresent() {
        // "冲突"（RELATIONSHIP 高优先）应压过 "经过/发生"（HISTORICAL）
        assertThat(classifier.classify("冲突是如何发生并发展的")).isEqualTo(QueryIntent.RELATIONSHIP);
    }

    @Test
    void constructionProducesUpToTwoQueriesWithIntent() {
        List<RetrievalQuery> qs = service.construct("query", "两人的关系是如何形成的？");
        assertThat(qs.size()).isBetween(1, 2);
        assertThat(qs.get(0).intent()).isEqualTo(QueryIntent.RELATIONSHIP);
        assertThat(qs.get(0).priority()).isZero();
        // 意图倾向扩展存在，且包含倾向词
        if (qs.size() == 2) {
            assertThat(qs.get(1).intent()).isEqualTo(QueryIntent.RELATIONSHIP);
            assertThat(qs.get(1).text()).contains("关系");
            assertThat(qs.get(1).priority()).isEqualTo(1);
        }
    }

    @Test
    void recentPlotDoesNotExpand() {
        List<RetrievalQuery> qs = service.construct("query", "最近主角怎么了");
        assertThat(qs.size()).isEqualTo(1); // RECENT_PLOT 不追加倾向词
        assertThat(qs.get(0).intent()).isEqualTo(QueryIntent.RECENT_PLOT);
    }

    @Test
    void deterministicSameInputSameOutput() {
        String input = "两人之间的矛盾是如何升级的？";
        List<RetrievalQuery> a = service.construct("q", input);
        List<RetrievalQuery> b = service.construct("q", input);
        assertThat(a).isEqualTo(b); // 完全一致（含 intent/text/priority/rationale）
    }

    @Test
    void neverMoreThanThreeQueries() {
        for (int i = 0; i < 50; i++) {
            List<RetrievalQuery> qs = service.construct("q", "第" + i + "个问题：他们的关系、修炼体系、伏笔、经历、" + i);
            assertThat(qs.size()).isLessThanOrEqualTo(3);
        }
    }

    @Test
    void emptyAndMalformedDoNotCrash() {
        assertThat(service.construct("q", "")).isEmpty();
        assertThat(service.construct("q", null)).isEmpty();
        assertThat(service.construct("q", "   ")).isEmpty();
        assertThat(classifier.classify(null)).isNotNull();
    }
}
