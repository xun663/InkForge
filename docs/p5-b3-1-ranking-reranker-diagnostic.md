# P5-B3-1 Ranking / Reranker Diagnostic 报告

## 1. 目标
在 Top-K=30 + Rank-Preserving Context 已固定后，逐层定位 Gold 从 Retrieval 到最终 Context 的损失：
Candidate Generation → BM25/Vector → RRF/Fusion → Reranker → Context，判断"当前 Reranker 是否有价值、
损失到底在哪一层"。纯诊断，不改生产、不切新模型。

## 2. 当前 Pipeline（读码确认）
```
BM25(top-30) + Vector(top-30) → RRF(k=60) → Fusion(top-30) → Reranker → Final(top-30) → rank-preserving Context
```
- 生产默认 Reranker = **PassThroughReranker**（identity，`reranker: ${INKFORGE_RERANKER:passthrough}`）→
  Final == Fusion(top-30)。**代码内唯一真实 reranker = LlmListwiseReranker**（只对 fusion 前
  rerankMaxCandidates=15 重排、输出 ≤15；仅 `reranker=llm` 时启用）。
- Context：P5-B3-0 rank-preserving；retrieved-memory 区段预算 R≈1000 tokens（bodyBudget 989）。

## 3. 固定变量
Final/Fusion/BM25/Vector Top-K=30、RRF k=60、coverage=48 同一 Memory（一次 deepseek 提取）、BGE-M3、
QueryBuilder/QueryIntent/Memory/Embedding/8192 Context/Prompt/LLM 全冻结。10 条定向 Query，38 条 gold。

## 4. Gold 分层追踪（节选，全表见 target/e2e/reranker-diagnostic/reranker-diag.md）
| Q | intent | gold | BM25 | Vec | Fusion | Rerank输入 | Rerank输出 | Δ | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| B1 | RELATIONSHIP | ch10 | 5 | 22 | 11 | 11 | **1** | +10 | RERANKER_HELPED |
| B1 | RELATIONSHIP | ch18 | 16 | 12 | 5 | 5 | 14 | −9 | RERANKER_HURT |
| B1 | RELATIONSHIP | ch39 | 12 | 15 | 23 | ∅ | ∅ | 0 | RANKING_MISS |
| B1 | RELATIONSHIP | ch48 | ∅ | 17 | 29 | ∅ | ∅ | 0 | RANKING_MISS |
| C1 | FORESHADOWING | ch37 | 1 | 1 | 1 | 1 | 1 | 0 | RERANKER_NEUTRAL |
| C1 | FORESHADOWING | ch47 | 6 | ∅ | 15 | 15 | **5** | +10 | RERANKER_HELPED |
| C2 | FORESHADOWING | ch26 | 28 | 28 | 25 | ∅ | ∅ | 0 | RANKING_MISS |

## 5. 汇总（PassThrough=生产默认 vs LlmListwiseReranker，同一候选池）
| 指标 | PassThrough | Llm Reranker |
|---|---|---|
| 平均 Recall | **0.975** | 0.865 |
| 平均 MRR | 0.808 | **0.950** |
| 平均 NDCG | **0.778** | 0.745 |
| Gold 覆盖（入最终列表） | **37/38** | 32/38 |
| Gold@Top5 / @10 / @15 | 22 / 27 / 32 | 20 / 28 / 32 |

## 6. 分层（38 条 gold）
- **CANDIDATE_MISS（未进 fusion top-30）= 1**：A2 ch37（BM25∅、Vector rank21 → RRF/fusion-30 截掉）。
- **RANKING_MISS（进 fusion 但 fusion rank>15，进不了 rerank 输入）= 5**：B1 ch39@23、B1 ch48@29、
  B2 ch3@18、C2 ch26@25、A1 ch41@19。
- 进 rerank 输入的 32 条：**HELPED 13 / HURT 12 / NEUTRAL 7**（verdict：HELP，仅微弱多数）。
- **进 fusion 且进最终 Context（rank-preserving）YES=14 / NO=23**。

## 7. 关键结论
1. **Retrieval/Fusion 已很强**：37/38 gold 在 fusion top-30；真正 Candidate/Fusion 层漏 = 1（A2 ch37，
   vector-only rank21 被 fusion-30 截）。
2. **当前默认 Reranker = PassThrough = identity**：rerank 层现在不改变任何排序，不是损失来源，也没有收益
   （它不是"真实 reranker"）。代码里可用的 Llm reranker：**能重排**（MRR 0.808→0.950；B1 ch10 从 fusion11 提到 #1，
   C1 ch47@15→#5），但因为它只吃 fusion 前 15 并只输出 ≤15：**丢 5 条 fusion rank16-30 的 gold
   （覆盖 37→32）**，NDCG 略降 → 对"evidence 进有效 Top"是**负到边际**。结论：当前任务不是缺"更会排名的
   reranker"。
3. **主导剩余损失 = Context Capacity**：23/37 进 fusion 的 gold 没进最终 retrieved-memory context——
   区段预算 ~989 tokens，只容前 ~2-3 个 chunk。即使 reranker 把某 gold 提到 #1（如 B1 ch10），也只有一个
   槽位能让它进；把 15 条/30 条里的其它 gold 全救不进来。这是冻结的区段容量问题，不是 ranking 能解的。
4. RRF 无额外系统性损失（fusion 序 = RRF 序，gold 多在前 15）；BM25/Vector 互补仍在（B1 ch48 仅 Vector、
   A2 ch37 仅 Vector 中后位被 fusion-30 截）。

## 8. 回答核心问题
> Top-K=30 + rank-preserving Context 下，主要损失层 = **D. Context Capacity（retrieved-memory ~989-token 区段
> 只容前 2-3 chunk，23/37 进 fusion 的 gold 到不了最终 Context）**；次为少量 Candidate/Fusion 尾部
> （1 条不进 fusion-30 + 5 条 fusion rank16-30 若走 15 上限会丢）；Reranker 不是瓶颈（默认 passthrough identity，
> 可选 Llm 有排序能力但对 evidence 覆盖边际为负）。

## 9. 是否值得继续优化 Reranker
**否。** Reranker verdict ≈ NEUTRAL/marginal：当前默认（PassThrough）是 identity；项目自有的 Llm reranker
能提 MRR 但降 coverage（37→32）与 NDCG，且救不回被 Context 容量挡住的 23 条。不应切默认、不应上第二个
reranker / Ensemble。

## 10. 下一步建议
- **进入 P5-C End-to-End（Memory ON/OFF）**（路线图最后最重要的实验）。
- 若要再投入，证据指向 **Memory Representation / Context 容量**：单 chunk 文本过大导致 ~989-token 区段只容
  2-3 条 → 压缩/更短 chunk/跨章聚合可能比再排一次名更能让 gold 进 context。这些属后续（本阶段冻结，不实现）。
- 不无限调 Retrieval。

## 11. 产物与复现
- 存档：`backend/target/e2e/reranker-diagnostic/reranker-diag.md`
- 复现：`RerankerDiagnosticAblation`（live，需 LLM_API_KEY + localhost:8085 bge-m3）；纯工具 `RerankDiagnostics`
  及其单测 `RerankDiagnosticsTest`（离线）。生产代码未改。
