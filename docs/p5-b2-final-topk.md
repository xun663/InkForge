# P5-B2 Final Top-K 终选报告（P5-B2-2 → P5-B2-2.5）

> **最终决定（P5-B2-2.5）**：默认 Final/Rerank Top-K = **30**。
> 证据：8 / 15 / 30 三值单变量 A/B（同一次共享 Memory）平均 Recall/EvCov（章节口径）
> `0.742 → 0.815 → 0.930`；30 是唯一能恢复 B1 长尾（ch48@23、ch39@27）与 **C2 ch26@26**
> 的值，@30 即 fusion 候选池全量上限（不再有任何 Final 截断）。噪声代价大（84→214 chunk），
> 但受冻结的 retrieved-memory ≤1024-token 区段截断，不会撑爆 8192 预算。

---

## 1. P5-B2-2.5 三值 A/B（8 / 15 / 30）

### 目的
P5-B2-2（8 vs 15）已证明 8 明显偏窄、15 有显著收益，但 15 是否是当前管线最优未证。本阶段只做
最后一次 Top-K A/B，确定 8 / 15 / 30 中的最优值，之后不再微调 Top-K。

### 方法（可靠性关键）
- **同一次 Memory Extraction**：coverage=48（真实 deepseek 提取一次），8/15/30 三个 rerankTopK 实例共用
  同一 chunk/embedding 池 —— 跨 K 的 Δ 无 deepseek 非确定性干扰。
- 其余全冻结：同 10 条 P5-0.5 定向 Query/gold、BGE-M3、BM25/Vector top-30、RRF k=60、fusion top-30、
  QueryBuilder/QueryIntent/Reranker=passthrough/Context/Prompt/LLM。
- 口径：gold=章节级。Recall=EvCov=最终 top-K 命中 distinct gold 章节/gold；MRR/NDCG 按 top-K 列表章节去重
  （DCG 1/log(i+2)，与 Metrics 一致）；Noise=最终 top-K 中非 gold 章节的 chunk 数；Gold Ratio=gold chunk/槽位。
- 复现：`RetrievalTopKFinalAblation`；存档 `target/e2e/retrieval-topk-final/final-topk-ab.md`。

### 汇总（10 条 Query，gold 总数 38）
| 指标 | @8 | @15 | @30 |
|---|---|---|---|
| 平均 Recall / Evidence Coverage（章节口径） | **0.742** | **0.815** | **0.930** |
| Gold Chapter Hit 合计（distinct，跨 query） | 28/38 | 31/38 | 36/38 |
| 平均 MRR@K | 0.808 | 0.808 | 0.808 |
| 平均 NDCG@K | 0.707 | 0.738 | 0.779 |
| Noise chunk 合计 | 34 | 84 | 214 |
| Context 槽位合计（Σmin(K, fusion长)） | 80 | 150 | 300 |
| Gold Ratio（chunk 级证据密度） | 57.5% | 44.0% | 28.7% |

MRR 恒 0.808（首命中 rank 不受扩窗影响）；NDCG 单调上升。**@30 = fusion 候选池上限**：Retrieval 层不再
有任何 Final 截断，仅剩的 2/38 漏（B1 ch18、D2 其一）已不在 fusion-30 内（候选生成漏，非截断）。

### 逐 Query EvCov / Noise
| Q | gold | fusion∋ | EvCov@8 | @15 | @30 | 噪声@8 | @15 | @30 |
|---|---|---|---|---|---|---|---|---|
| A1 苦海 | 4 | 4/4 | 3/4 | 3/4 | 4/4 | 4 | 9 | 21 |
| A2 修炼基础 | 4 | 4/4 | 3/4 | 3/4 | 4/4 | 4 | 9 | 21 |
| **B1 庞博** | 5 | 4/5 | 2/5 | 2/5 | **4/5** | 6 | 13 | 26 |
| B2 刘云志 | 5 | 5/5 | 4/5 | 5/5 | 5/5 | 2 | 7 | 19 |
| **C1 荒古圣体** | 3 | 3/3 | 2/3 | 3/3 | 3/3 | 4 | 10 | 25 |
| **C2 九龙拉棺** | 4 | 4/4 | 3/4 | 3/4 | **4/4** | 4 | 10 | 24 |
| C3 荒古禁地 | 5 | 5/5 | 4/5 | 5/5 | 5/5 | 2 | 3 | 16 |
| D1 百草液 | 3 | 3/3 | 3/3 | 3/3 | 3/3 | 0 | 6 | 20 |
| D2 古经 | 2 | 1/2 | 1/2 | 1/2 | 1/2 | 6 | 13 | 28 |
| D3 韩飞羽 | 3 | 3/3 | 3/3 | 3/3 | 3/3 | 2 | 4 | 14 |

### B1 / C1 / C2（重点）
- **B1（庞博关系）**：`2/5 → 2/5 → 4/5`。@15 相对 @8 **无新增**（下一 gold ch48@23、ch39@27 都 >15）；
  只有 **@30** 把 ch48、ch39 收入（4/5）。ch18 不在 fusion-30（∅）——候选生成漏，Top-K 永远救不了。
- **C1（荒古圣体）**：`2/3 → 3/3 → 3/3`。@15 已满恢复（ch47@12），@30 保持。
- **C2（九龙拉棺）**：`3/4 → 3/4 → 4/4`。**ch26@rank26 在 @30 成功进入**（本阶段最关键验证用例）——
  它只在 30 进；@15 与 @8 同（3/4）。

### 扩窗代价
- **8→15**：新增 70 槽 —— gold chunk 20（29%）、噪声 50（71%）；新增命中章节 3（0.30/Query）。
- **15→30**：新增 150 槽 —— gold chunk 20（13%）、噪声 130（87%）；新增命中章节 5（0.50/Query）。

### 工程权衡与决定
判断属于"**情况 B：8 < 15 < 30**"（30 带来明确 EvCov 提升，且是唯一恢复 C2 ch26 / B1 长尾者）。
按本轮实验原则（30 明显领先 → 默认 30；15 并不接近 30），**默认取 30**：
1. @30 消除 Retrieval 层的 Final 截断（=fusion 池全量），把 B1/C2 的关系·伏笔长尾证据放行——这正是
   P5-B 系列从 P5-0.5 起反复丢失的证据类型。
2. Noise 虽大（214），但被冻结的 retrieved-memory 区段（≤1024 token、priority 5）在 prompt 侧截断，
   不会撑爆 8192 预算；池子大 ≠ 全进 prompt。
3. MRR 不降、NDCG 升至 0.779；为 P5-B3（Ranking/Reranker）提供"无截断混淆"的完整候选，便于定位
   "进池却排错"的根因。
- **保留 15 作为低噪备选**：若后续产品以"prompt 噪声优先"为准，15 用约 1/2.5 的池噪声换取大部分收益
  （C1/B2/C3 满，31/38）；但它会继续把 C2 ch26、B1 ch48/ch39 留在池外（截断），不满足本系列目标。

### 新发现（冻结层，不改，交 P5-B3）
`MemoryAwareContextBuilder` 对 retrieved-memory 区段也用 `fitTail`（保留尾部、从头部逐段裁掉）做 token
截断，而 retrieved 结果是"最佳在前"渲染 → 一旦区段内容超预算，**最高分（最可能是 gold 的头部）被裁掉、
只留低分尾部**。这可能解释 P5-0/0.5 反复出现的"final context 只命中近期、早期 gold 不见"。这是 Context
层（冻结）的潜在病根，优先级高于继续调 Top-K，应纳入 P5-B3。

---

## 2. 生产配置修改（最终值 = 30）
- `application.yml`：`inkforge.retrieval.rerank-top-k: 15 → 30`
- `RetrievalProperties` 兜底默认 `rerankTopK = 15 → 30`
- `HybridRetrievalService` 类注释 "Reranker(top 15)" → "(top 30)"（纯注释）

其余检索参数（BM25/Vector top-30、RRF k=60、fusion top-30、reranker、rerank-max-candidates=15 等）未动。
注：passthrough + top-30 = fusion 池全量；若将来切 LLM reranker，rerank-top-k=30 会受 rerank-max-candidates=15
上限自然截到 15，行为仍安全。

## 3. 回归
默认套件（排除需 8085 BGE 的 EmbeddingAblationTest）：**260 run / 0 fail / 0 error / 10 skip**（Docker 门控 *IT），
BUILD SUCCESS。P5-A / P5-B1 / P5-B2 相关测试不受影响，无新增失败。前端未改。

## 4. 对 P5-B3 的影响
默认 30 后，Retrieval 层 Final 截断不再是瓶颈；剩余漏全部落在**候选生成**（B1 ch18、D2 其一 ∉ fusion-30）
与**排序/rerank/上下文表示**（关系·伏笔需跨章聚合、retrieved-memory prompt 截断）。P5-B3 应聚焦：
BM25/Vector/RRF 对长关系与伏笔的排名、LLM Reranker 价值、以及 retrieved-memory 区段截断方向。
> 不要再做 Top-K 微调（8/12/15/18/20/24… 之类）。

---

## 附录：P5-B2-2（8 vs 15，历史，已被 B2-2.5 取代）
（略——原 B2-2 报告结论为"默认 15"；B2-2.5 三值实验后最终定为 30。改动与 A/B 方法同源，见 §1。）

> 数据存档：`backend/target/e2e/retrieval-topk-final/final-topk-ab.md`（B2-2.5 覆盖 B2-2 该文件）
> 复现：`RetrievalTopKFinalAblation`（backend，需 LLM_API_KEY + localhost:8085 bge-m3）
