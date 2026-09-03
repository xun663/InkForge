package com.inkforge.retrieval;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * P5-B1 Query-aware Query Construction：给定一个查询文本，先确定性分类意图，
 * 再构造 1~3 条对该意图 Memory 类型更敏感的检索表达。
 *
 * <p>约束：
 * <ul>
 *   <li>纯 Java 确定性（无 LLM / 无随机 / 无当前时间）——同一输入 → 完全一致输出。</li>
 *   <li>单次最多 3 条查询。</li>
 *   <li>不含任何小说专名硬编码（通用词汇）。</li>
 *   <li>rationale 只进 trace/debug，不参与 BM25/Vector。</li>
 * </ul>
 *
 * <p>构造策略：Query#1 = 原始文本（保 recall）；Query#2 = 原始文本 + 该意图的检索倾向词
 * （让 BM25/Vector 更容易命中该类型记忆的词汇）。意图为 RECENT_PLOT 时不追加（避免噪声）。
 */
@Service
public class QueryConstructionService {

    private static final int MAX_QUERIES = 3;

    private final QueryIntentClassifier classifier;

    public QueryConstructionService(QueryIntentClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 构造针对该查询意图的检索表达（1~2 条；≤3 硬上限）。
     */
    public List<RetrievalQuery> construct(String type, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        QueryIntent intent = classifier.classify(queryText);
        List<RetrievalQuery> queries = new ArrayList<>(2);

        // Query#1：原始表达（确定性，最高优先级）
        queries.add(new RetrievalQuery(type, queryText, intent, 0, "原始查询 · intent=" + intent));

        // Query#2：意图倾向表达（仅当有倾向词且非 RECENT_PLOT）
        if (intent != QueryIntent.RECENT_PLOT) {
            List<String> terms = classifier.retrievalTerms(intent);
            if (!terms.isEmpty()) {
                String expanded = queryText + " " + String.join(" ", terms);
                queries.add(new RetrievalQuery(type, expanded, intent, 1,
                        "意图倾向扩展 · " + intent + " 词汇=" + terms));
            }
        }
        return queries;
    }

    public QueryIntent classify(String queryText) {
        return classifier.classify(queryText);
    }
}
