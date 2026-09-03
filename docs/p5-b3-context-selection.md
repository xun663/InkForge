# P5-B3-0 Context Selection Diagnostic 报告

## 1. 问题背景
P5-B2-2.5 把 Final Top-K 定为 30（=fusion 池全量），Retrieval 层 Gold EvCov 已达 ~0.92。但此前 P5-0/0.5
长期观察到"final context 只命中近期、早期 gold 不进 prompt"。怀疑：即使 Retrieval 找到 gold，**Context
Selection（retrieved-memory 区段按 token 预算截断）也可能把高排名 gold 丢掉**。本阶段只诊断这个：
> Top-K=30 找到的 Gold，有多少死在 Context Selection，而不是死在 Retrieval？

## 2. 当前 ContextBuilder 实现事实（读码确认，非猜）
- `MemoryAwareContextBuilder`（冻结，phase-2 多 section 组装，8192 budget）：
  - `retrieved-memory` section：priority 5、max **1024** tokens、非 required（application.yml == DEFAULT_SECTIONS）。
  - 各 section 先 `allocate()`（required min 预留 → 按 priority 贪心 top-up 到 max），再 `renderSection` 对正文做 `fitTail`。
  - `renderRetrievedMemory`：把 `retrieved.results()`（**score 降序 = 高分在前**，见 `DefaultRetrievedMemoryProvider.mergeByChunkId`）逐条拼成一个 body。
  - `fitTail`：超预算时**反复从头部裁 10%、保留尾部**（该语义本为"断点要保留最近原文尾部"设计，被 retrieved 复用）。
- 实测该小说 retrieved-memory 区段实际分得 **R=1000 tokens**（fixed=280，8192−其余 section 上限 6912−…=1000；bodyBudget≈989）。

### 关键 mismatch（确认存在）
retrieved body 高分在前、fitTail 保尾 → 一旦 body 超预算，**最高分（最可能是 gold）的头部整段被裁，只剩低分尾部**。
与 P5-0/0.5"final context 只见近期 3-5 条、早期 gold 消失"症状吻合。

## 3. 方法（单变量 A/B，不重跑 Retrieval）
- 固定：coverage=48 Full Memory、10 条 P5-0.5 定向 Query/gold、BGE-M3、BM25/Vector top-30、RRF k=60、
  fusion top-30、rerank-top-30、Query/QueryIntent/Reranker=passthrough/8192/Prompt/LLM，全冻结。
- 唯一变量 = retrieved-memory **Context Selection 策略**：
  - **A = 当前生产 fitTail**（保尾）
  - **B = Rank-Preserving**（按 rank 从 1 保序累加，下一个放不下即停，不删前面；单个超长 rank1 用 fitTail 兜底）
- 同一 top-30 检索结果分别过 A、B，budget 同为真实 R=1000（bodyBudget 989）。
- 复现：`ContextSelectionAblation`（live）+ `RetrievalSelectionSim`（test，A/B 模拟，A 逐字复刻生产）；
  存档 `target/e2e/context-selection-ablation/context-selection-ab.md`。

## 4. 核心结果
| 指标 | A（当前 fitTail） | B（Rank-Preserving） |
|---|---|---|
| Retrieval EvCov（gold 进 top-30） | **35/38 (0.921)** | 35/38 (0.921) |
| Context EvCov（gold 进最终 retrieved-memory） | **3/38 (0.079)** | **14/38 (0.368)** |
| Context Selection Retention（ctx/retr） | 3/35 (0.086) | 14/35 (0.400) |
| Context Selection Loss（retr−ctx） | **32** | 21 |
| 保留 distinct 章节 / token 合计 | 31 / 9548 | 22 / 7437 |

**Retrieval 已把 35/38 gold 找到，当前 Context Selection 只让它 3/35 活下来（8.6%）——Context Selection
是当前主导瓶颈（远超 Retrieval）。Rank-Preserving 把它救到 14/35（40%，×4.7）。**

## 5. 逐 Query（A vs B：Context gold / gold）
| Q | gold | Retr∋ | A ctx | B ctx | 说明 |
|---|---|---|---|---|---|
| A1 | 4 | 3/4 | 1/4 | 1/4 | |
| A2 | 4 | 4/4 | 1/4 | 2/4 | B 保留 ch47@1 |
| **B1** 庞博 | 5 | 3/5 | 0/5 | 1/5 | A 连 rank2 gold(ch18) 都裁；B 保 ch18@2 |
| B2 刘云志 | 5 | 5/5 | 0/5 | 1/5 | A 全丢（含 rank2 ch19）；B 保 ch19@2 |
| **C1** 荒古圣体 | 3 | 3/3 | 0/3 | 1/3 | A 连 **rank1 ch37** 都裁；B 保 ch37@1 |
| **C2** 九龙拉棺 | 4 | 4/4 | 0/4 | 1/4 | A 全丢；B 保 ch5@2 |
| C3 荒古禁地 | 5 | 5/5 | 1/5 | 2/5 | |
| D1 | 3 | 3/3 | 0/3 | 2/3 | |
| D2 | 2 | 2/2 | 0/2 | 0/2 | 预算仍不足，两者都丢 |
| D3 | 3 | 3/3 | 0/3 | **3/3** | A 全丢，B 全保 |

B 在每一条都 ≥ A；尤其 C1 rank1 gold、B1/B2 rank2 gold 在 A 下被"裁头"丢弃——**这是确定性的 rank/context
mismatch，不是偶然**。B1 ch10/ch39 本轮未进 top-30（Retrieval 层样本性漏，仍属 Retrieval/Candidate 侧）。

## 6. B 为何仍只保 40%
retrieved-memory 区段真实预算 ~1000 tokens（受前 4 个更高优先级 section 贪心吃满所剩），而单条 memory
chunk 文本较大 → 预算只能容纳前 ~2-4 条。B 保证这 2-4 条是**最高分（最相关）**，A 却保证它们是最低分。
剩余 60% 丢 = **区段 token 容量太小**（冻结；本阶段禁止加预算）。这是"容量"问题，与"顺序"问题正交。

## 7. 工程判断与落地
- 结论：**情况 A —— Retrieval Coverage 高、Context Coverage 明显低；Context Selection 是真实瓶颈**。
- B 明显改善（0.079→0.368，逐条 ≥ A，无副作用）→ **正式落 Rank-Preserving**。
- 生产改动（最小、仅 retrieved-memory）：
  - `MemoryAwareContextBuilder`：retrieved-memory 分支改用 rank-preserving 保序选择（新增
    `renderRetrievedMemory(retrieved, tokenBudget)` + static `rankPreservingRetrievedBody`）；
    `fitTail` 改为 static（其余 section——breakpoint/recent-chapters/current-facts 等时间序区段——语义不变）。
  - 未改：Top-K/RRF/BM25/Vector/Reranker/Query/QueryIntent/Memory/Embedding/Prompt/Context Budget。
- 回归：**271 run / 0 fail / 0 error / 10 skip**（含新增 11 个 selection 测试），BUILD SUCCESS。

## 8. 对 P5-B3 后续（Ranking / Reranker）影响
Retrieval 层已非主要瓶颈；Context Selection 顺序问题已修正。下一步 P5-B3-1 应聚焦 **Ranking / Reranker**
（含 B1 ch10/ch39 这类"gold 连 top-30 都进不去"的候选生成侧 + 长期关系/伏笔是否系统性排名偏低），
以及 retrieved-memory **区段容量**（~1000 token 太小）作为待办（需解冻 Context 预算/更长 representation 才可动）。

## 9. 产物
- 诊断存档：`backend/target/e2e/context-selection-ablation/context-selection-ab.md`
- 复现：`ContextSelectionAblation`（live，需 LLM_API_KEY + localhost:8085 bge-m3）、
  `RetrievalSelectionSim`/`MemoryAwareContextBuilderSelectionTest`（离线）。
