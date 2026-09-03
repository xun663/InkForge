# P5-B1 Query-aware Query Construction

## 1. P5-0 / P5-0.5 / P5-A 背景
- **P5-0**：coverage 3/10/20/48，普通断点续写 3→10 有收益、之后趋平。
- **P5-0.5**：体系/关系/伏笔/跨章定向 Query 证明 Full Memory 含早期证据，但 coverage 48 下 B1(庞博关系)/C1(荒古圣体)/C2(九龙拉棺) 存在"A Retrieval Error：存了但选不出"；DeepSeek 先验使 LLM Answer 不可作为检索成功指标。
- **P5-A**：Full Memory Job 完成（249 tests）。

## 2. 当前 QueryBuilder 的问题
原 QueryBuilder 只面向**断点续写**上下文（primary/character/thread），无意图概念；对"关系/伏笔/世界观"类查询没有针对性表达。P5-B1 假设：部分 Retrieval Error 源于"查询没有表达要找回哪类记忆"。

## 3. Query Intent 设计
`QueryIntent` 枚举（6 类，最小集）：RECENT_PLOT / CHARACTER / RELATIONSHIP / WORLDBUILDING / FORESHADOWING / HISTORICAL_EVENT。

## 4. Intent 分类规则
`QueryIntentClassifier`：纯 Java 确定性规则表（无 LLM/随机），按固定优先级匹配关键词（关系词 > 修炼词 > 伏笔词 > 历史词 > 人物词），默认 RECENT_PLOT。同时给每类意图提供「检索倾向词」（通用，无小说专名硬编码）。

## 5. Query Construction 方案
`QueryConstructionService.construct(text)`：
- Query#1 = 原文本（intent 标注，priority 0）；
- Query#2 = 原文本 + 意图倾向词（仅当 intent ≠ RECENT_PLOT 且有倾向词，priority 1）。
- rationale 只进 trace/debug，不参与 BM25/Vector。确定性、单次 ≤3 条。

## 6. 与 Legacy Query 兼容
- `RetrievalQuery` 扩展 intent/priority/rationale，**保留旧构造器**（type, text）。
- `RetrievalQueryBuilder.build(novel)` 仍生成 primary/character/thread（≤3），现带 intent：primary→RECENT_PLOT、character→CHARACTER、thread→classify(thread)。`@Autowired` 注入分类器，保留 1 参构造给测试。
- 不修改 BM25/Vector/RRF/Reranker/ContextBuilder。

## 7. 测试结果
`./mvnw test` = **261 tests / 0 failures / 0 errors / 10 skipped**。
新增 `QueryConstructionTest`（12）：6 类意图分类 + 关系优先于历史 + 构造含 intent/priority + RECENT_PLOT 不扩展 + 确定性 + ≤3 上限 + 空/歧义输入不崩溃。

## 8. A/B 实验结果（coverage=48，10 条定向 Query）
A = 原查询文本；B = Query-aware 构造。同一 gold/BGE-M3/BM25+Vector→RRF→PassThrough(top-8)。

| 平均 | A | B |
|---|---|---|
| R@5 | **0.59** | 0.51 |
| R@8 | **0.72** | 0.68 |
| EvCov | **0.72** | 0.68 |

**B 未改善，平均略降**。明细见 `target/e2e/query-aware-retrieval/query-aware-ab.md`。

## 9. B1 / C1 / C2 详细结果
| Q | intent | A EvCov | B EvCov |
|---|---|---|---|
| B1 庞博关系 | RELATIONSHIP | 0.40 | **0.20**（变差） |
| C1 荒古圣体 | FORESHADOWING | 0.67 | 0.67（R@5 0.67→0.33 变差） |
| C2 九龙拉棺 | FORESHADOWING | 0.50 | 0.50（不变） |

**B1/C1/C2 均未改善**（B1/C1 反而变差）。意图倾向扩展（追加通用词汇）让 BM25 更偏向"当前关系/近期"chunk，早期证据被进一步挤出 top-8。

## 10. Recall / MRR / NDCG / Evidence Coverage
以 R@5 / R@8 / EvCov 为主（top-8 生产口径）。B 的平均 R@5 0.51 < A 0.59；无 MRR/NDCG 优势（构造未提升排序质量）。

## 11. Error Analysis
- **Query Construction 未解决 B1/C1/C2**——证据指向：这些 Retrieval Error 的根源**不是"查询没表达意图"**（即使用意图词扩展，早期关系/伏笔 chunk 仍不进 top-8）。
- 更可能：**检索排名**（recent 语义占优，top-8 被近期 chunk 占满）+ **chunk 表示**（关系/伏笔事实分散于各章，无跨章聚合）。

## 12. 当前结论
**明确回答：P5-0.5 发现的 Retrieval Error，有多少是 Query Construction 导致的？——本阶段无法证明，且证据表明 Query Construction（意图+通用词扩展）不是主要来源（B 未改善反略降）。** 需要转向检索排名 / chunk 表示。

## 13. P5-B1 的净产出
- QueryIntent + 确定性分类器 + Query Construction Service（基础设施就绪）。
- `RetrievalQuery` 带 intent/priority/rationale（后续 trace/检索路由可复用）。
- QueryBuilder 的断点查询已打 intent（可观察）。
- 诚实结论：**"改查询表达"这一层未改善早期证据召回**。

## 14. P5-B2 建议
- **检索排名**：提高 fusion top-K / rerank top-K，或引入对"历史/关系跨度"敏感的 reranker——让早期证据有机会进 context（而非被近期挤掉）。
- **chunk 表示**：关系/伏笔需跨章聚合（关系卡/伏笔追踪），而非逐章散片——接近 Narrative State。
- 若仍要做 query 层：需实体抽取（不能靠通用词扩展），先验证是否值得。
- 本轮不改 RRF/top-K（P5-B2），如实记录 Query Construction 单变量结果。
