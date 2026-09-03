package com.inkforge.retrieval;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P5-B1 确定性意图分类（纯规则，无 LLM、无随机——保证 benchmark 可重复）。
 *
 * <p>规则表 + 固定优先级；同一条文本命中多个意图时取最高优先级。默认 RECENT_PLOT。
 * 同时提供每个意图的「检索倾向词」——用于构造对记忆类型更敏感的检索表达（通用词汇，不含任何小说专名）。
 */
@Component
public class QueryIntentClassifier {

    /** 命中 → 意图。优先级按表内顺序（靠前者优先）。关键词为中文子串匹配。 */
    private static final List<Map.Entry<QueryIntent, List<String>>> RULES = List.of(
            Map.entry(QueryIntent.RELATIONSHIP, List.of(
                    "关系", "之间", "冲突", "敌对", "合作", "师徒", "结识", "朋友", "结盟", "反目", "矛盾", "结怨", "恩怨")),
            Map.entry(QueryIntent.WORLDBUILDING, List.of(
                    "体系", "修炼", "境界", "规则", "宗门", "势力", "世界", "设定", "组织", "地理", "开辟")),
            Map.entry(QueryIntent.FORESHADOWING, List.of(
                    "伏笔", "异常", "线索", "之前", "此前", "未解释", "为何", "以前", "隐藏", "疑点", "异象")),
            Map.entry(QueryIntent.HISTORICAL_EVENT, List.of(
                    "经过", "起因", "发生", "历史", "过程", "来历", "起源", "事件", "发展", "如何获得")),
            Map.entry(QueryIntent.CHARACTER, List.of(
                    "修炼基础", "状态", "属性", "实力", "身份", "性格", "特征", "外貌", "武器")));

    /** 检索倾向词（构造更敏感的检索表达）。通用，不含小说专名。 */
    private static final Map<QueryIntent, List<String>> RETRIEVAL_TERMS = Map.of(
            QueryIntent.RECENT_PLOT, List.of("当前", "最近", "处境", "现状"),
            QueryIntent.CHARACTER, List.of("状态", "实力", "身份", "经历", "特征"),
            QueryIntent.RELATIONSHIP, List.of("关系", "相识", "结盟", "敌对", "合作", "冲突", "师徒", "朋友", "反目"),
            QueryIntent.WORLDBUILDING, List.of("修炼", "体系", "境界", "规则", "开辟", "层次"),
            QueryIntent.FORESHADOWING, List.of("异常", "线索", "伏笔", "与众不同", "异象", "疑点"),
            QueryIntent.HISTORICAL_EVENT, List.of("事件", "经历", "过程", "起因", "发展"));

    public QueryIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryIntent.RECENT_PLOT;
        }
        for (Map.Entry<QueryIntent, List<String>> rule : RULES) {
            for (String kw : rule.getValue()) {
                if (query.contains(kw)) {
                    return rule.getKey();
                }
            }
        }
        return QueryIntent.RECENT_PLOT;
    }

    /** 某意图的检索倾向词。 */
    public List<String> retrievalTerms(QueryIntent intent) {
        return RETRIEVAL_TERMS.getOrDefault(intent, List.of());
    }
}
