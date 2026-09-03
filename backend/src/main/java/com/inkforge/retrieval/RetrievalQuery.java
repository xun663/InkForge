package com.inkforge.retrieval;

/**
 * 一条确定性检索查询。type 保留 P3 的角色语义（primary/character/thread）；
 * P5-B1 增加 intent / priority / rationale，用于意图可观察与（后续）检索路由。
 * rationale 只用于 trace/debug，不参与 BM25/Vector 搜索。
 */
public record RetrievalQuery(String type, String text, QueryIntent intent, int priority, String rationale) {

    /** P3 兼容构造：默认 RECENT_PLOT / priority 0 / 无 rationale。 */
    public RetrievalQuery(String type, String text) {
        this(type, text, QueryIntent.RECENT_PLOT, 0, null);
    }
}
