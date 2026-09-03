# P5-B2 Top-K Ablation 实验报告

## 1. 实验目标
判断 P5-0.5 中 B1/C1/C2 的"A Retrieval Error（存了但选不出）"根源是：
- **A**：Gold Evidence 根本没进候选池（BM25/Vector/RRF 都没找到），还是
- **B**：Gold 已进候选池，但最终 Top-K 太小被截断。

## 2. 当前 Retrieval Pipeline
```
BM25(top-30) ──┐
               ├─ RRF(k=60) → Fusion(top-30 候选池) → Reranker(PassThrough) → Final(top-8) → Context
Vector(top-30)─┘
```
- BM25 top-k = 30；Vector top-k = 30；RRF k = 60；**fusion top-k = 30（候选池上限）**；rerank top-k = 8（最终到 Context）。

## 3. Top-K 各层定义
| 层 | 值 | 说明 |
|---|---|---|
| BM25 Top-K | 30 | 关键词候选 |
| Vector Top-K | 30 | 语义候选 |
| **RRF Candidate Pool** | **30** | fusion 输出上限（候选池天花板） |
| Reranker Input | 30 | PassThrough 输入 |
| **Reranker Final / Context** | **8** | 真正进入上下文的条数 |

P5-0.5/B1 的"top-8" = **Reranker Final Top-K（8）**。

## 4. P5-0.5 问题回顾
B1(庞博关系)/C1(荒古圣体)/C2(九龙拉棺) 在 coverage 48 + top-8 下 EvCov 低（0.20/0.67/0.50）。

## 5. 实验变量
只评估 Fusion 候选在不同 K（8/15/30）下的 gold 覆盖；**不改** RRF k=60、fusion-top-30、reranker、Query、Memory、Context。

## 6. Candidate Coverage（核心结果）
10 条定向 Query 的 gold 章节在 **Fusion top-30 候选池**中的命中（∋）：

| Q | gold数 | Fusion∋ | Fusion@8 | Fusion@15 | Fusion@30 | 最前Fusion rank |
|---|---|---|---|---|---|---|
| A1 苦海 | 4 | **4/4** | 3/4 | 3/4 | 4/4 | 1 |
| A2 修炼基础 | 4 | 4/4 | 3/4 | 4/4 | 4/4 | 1 |
| B1 庞博关系 | 5 | **4/5** | 3/5 | 3/5 | 4/5 | 3 |
| B2 刘云志 | 5 | 5/5 | **2/5** | **5/5** | 5/5 | 4 |
| C1 荒古圣体 | 3 | 3/3 | 2/3 | **3/3** | 3/3 | 1 |
| C2 九龙拉棺 | 4 | 4/4 | **2/4** | **3/4** | **4/4** | 2 |
| C3 荒古禁地 | 5 | 5/5 | 5/5 | 5/5 | 5/5 | 1 |
| D1 百草液 | 3 | 3/3 | 3/3 | 3/3 | 3/3 | 1 |
| D2 古经 | 2 | 2/2 | 2/2 | 2/2 | 2/2 | 5 |
| D3 韩飞羽 | 3 | 3/3 | 3/3 | 3/3 | 3/3 | 1 |

**绝大多数 gold 已在候选池**（B1 4/5、C1 3/3、C2 4/4、B2 5/5）——只是 @8 太窄丢掉了（见 §9）。

## 7. BM25 / Vector 命中（互补性）
逐 gold 的 BM25/Vector/Fusion 最前 rank（详见 `target/e2e/retrieval-topk-ablation/topk-ab.md`）：
- **B1 ch10**：BM25 rank 7（找到），Vector ∅，Fusion rank 18 → **BM25 独有贡献**。
- **B1 ch39**：BM25 ∅，Vector rank 15，Fusion ∅ → **Vector 找到但被 fusion 丢弃**（唯一真正漏掉的 gold）。
- **C2 ch2/ch26**：BM25+Vector 都找到（6/26、1/29），fusion 保留但 rank 靠后。
- **A1 ch41**：BM25 ∅，Vector rank 17 → Vector 独有，fusion rank 23（@8/@15 丢）。
- 其余多数两路都命中。

**结论：BM25 与 Vector 互补**——关系/伏笔早期证据常只有一路命中（如 B1 ch10 仅 BM25、ch39 仅 Vector），两路缺一不可。

## 8. RRF 结果
RRF 正确保留了两路互补结果（大部分 gold 进了 fusion top-30）。**唯一反例 B1 ch39**：Vector rank 15 却被 fusion top-30 丢弃（RRF score = 1/(60+15) 太低，被 30 条更高分挤掉）→ fusion 候选上限 + RRF 排序对"仅单路中后位"结果的截断。

## 9. Reranker 结果
Reranker = PassThrough，只按 fusion 顺序截到 8。**Gold 是否进 Reranker = 是否在 fusion rank ≤8**：
- B1：gold 在 fusion rank 3/4/6/18/∅ → 仅 3 个进 top-8（@8=3/5）。
- C1：rank 1/3/14 → 2 个进 top-8。
- C2：rank 2/4/12/26 → 2 个进 top-8。
- B2：rank 4/6/10/11/14 → 仅 2 个进 top-8（@15=5/5 说明 15 才够）。

## 10. Top-K A/B
| 条件 | 显著变化 |
|---|---|
| **8 → 15** | B2 2/5→5/5、C1 2/3→3/3、C2 2/4→3/4（大幅恢复） |
| **15 → 30** | B1 3/5→4/5（ch10@18 进）、C2 3/4→4/4（ch26@26 进）、A1 3/4→4/4（ch41@23 进） |
| 30（候选上限） | 除 B1 ch39 外全部覆盖 |

## 11. B1 / C1 / C2 详细分析
- **B1（5 个 gold）**：4/5 在候选池。@8 只留 3（ch18@3、ch25@4、ch48@6）；ch10@18、ch39 需更大 K/改生成。**ch39 是唯一真漏**（Vector rank 15 被 fusion top-30 丢弃）。
- **C1（3 个 gold）**：3/3 全在候选池；ch47@14 → @15 恢复，@8 只 2 个。**纯截断**。
- **C2（4 个 gold）**：4/4 全在候选池；ch26@26、ch5@12 → @30 恢复 4/4。**纯截断（需较大 K）**。

## 12. Recall / MRR / NDCG
以候选池层 coverage 为准（本实验焦点是"gold 是否进候选池"，非排序指标）。fusion-30 下 gold 覆盖 96%（38/40 gold；B1 ch39 除外）。

## 13. Evidence Coverage
- @8：平均约 3.0/4.0 gold/query。
- @15：B2/C1 恢复满，C2 3/4。
- @30：除 B1 ch39 外全满。

## 14. Error Analysis
- **绝大多数 = Candidate Truncation（情况 1）**：gold 在 fusion top-30 内，但 final top-8 截断。
- **B1 ch39 = 真漏**：Vector rank 15，fusion 未保留（fusionTopK=30 上限 + RRF 低分截断）——属"候选生成/fusion 上限"边缘，非单一 Top-K。

## 15. 结论
**明确回答 §十九 八个问题：**
1. B1/C1/C2 的 gold 有进较大候选池吗？→ **是**（B1 4/5、C1 3/3、C2 4/4 在 fusion top-30）。
2. Top-K 8→15/30 提高 EvCov 吗？→ **是**（@8 平均 ~3.0 → @15 大部分恢复 → @30 除 B1 ch39 全满）。
3. 最小有效 Top-K？→ **~15**（B2/C1/D3 恢复）；**~30** 覆盖尾部（B1 ch10@18、C2 ch26@26）。
4. 没提高的在哪消失？→ 仅 B1 ch39（Vector@15 被 fusion 丢弃）。
5. BM25/Vector 谁擅长长期关系/伏笔？→ **互补**（B1 ch10 仅 BM25、ch39 仅 Vector）。
6. RRF 保留两路互补吗？→ 基本是；ch39 是反例（vector-only 中后位被 fusion 上限截断）。
7. Gold 进 Reranker 吗？→ PassThrough 只截断 fusion；rank≤8 的才进（多数被 @8 拒之门外）。
8. **主要瓶颈 = A：最终 Top-K 太窄（8）**；次要 = fusion 候选上限 30 + RRF 对单路中后位的排序（B1 ch39）。

**核心答案**：P5-0.5 的早期 Evidence，**主要进入了候选池（fusion top-30），被最终 Top-8 截断**；极少数（B1 ch39）被 fusion 上限丢弃。

## 16. P5-B2 下一步建议
- **首要**：把 **Final / Reranker Top-K 从 8 提到 15~30**（让进候选池的 gold 有机会进 Context）——先做纯 top-K 增量，看是否改善续写/context。
- **次要**：复查 fusion-top-k=30 上限与 RRF 对"仅单路（BM25 或 Vector）中后位"结果的保留（B1 ch39 类）——可能需要 fusion 侧保底（保留两路 union 中靠前的单路结果）。
- RRF k、Reranker、Memory 暂不动；这是诊断性结果，改 top-K 属 P5-B2 主实现。

> 数据存档：`target/e2e/retrieval-topk-ablation/topk-ab.md`（每 query × 每 gold 的 BM25/Vector/Fusion rank）。
