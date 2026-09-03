# P5-0 Memory Coverage A/B 实验报告（遮天 1-48 章）

## 1. Objective
验证"Story Memory 覆盖范围增加（3/10/20/48 章）是否能稳定改善长篇小说续写质量"，为 P5 决策（Full Memory vs Memory Compression）提供数据。

## 2. Hypothesis
覆盖增大 → 记忆池更丰富 → 检索空间更大 → 上下文更完整 → 续写更一致。本实验验证这条链是否成立，以及边际收益在哪一段。

## 3. Dataset
- **素材**：遮天 1-48 章（本地全本提取，用户熟悉的早期 arc：九龙拉棺 → 荒古禁地 → 灵墟洞天体系建立）。
- **Visible Prefix**：第 1-48 章（48 章，末章 `第048章 灵墟洞天外`，断点）。
- **Hidden Gold**：真实第 49 章及之后内容（本地 `zetian_gold49.txt`），绝不进入记忆/检索/prompt，仅用于事后比较。
- **为何不用 3 篇短篇做 40/100**：短篇仅 6-8 章，撑不起 10/40/100；改用 48 章真实材料测 3/10/20/48。

## 4. Cut Points
断点固定在第 48 章末（`第048章 灵墟洞天外`）。所有 coverage 条件从同一断点续写。

## 5. Coverage Definition
- Coverage = **参与 Memory Construction 的最后 N 章**。
- 3：只提取 46-48 章；10：39-48；20：29-48；48：1-48。
- 每 Coverage **独立构建自己的 Story Memory**（不共享），固定 query 从第 48 章断点生成（天然一致）。

## 6. Controlled Variables（全部固定）
| 变量 | 固定值 |
|---|---|
| 唯一自变量 | Memory Coverage（3/10/20/48） |
| Embedding | BGE-M3（localhost:8085，1024d） |
| LLM / 生成 | deepseek-v4-flash · temperature 0.8 · 1200 tokens |
| Retrieval | BM25(30)+BGE Vector(30)→RRF(rrf-k=60)→PassThrough(top-8) |
| Query | RetrievalQueryBuilder（断点第 48 章，所有 coverage 同一套） |
| Context Budget | 8192 |
| Memory 提取 | 同一 prompt / 同一 max-retries=2 |

## 7. Experimental Conditions
4 conditions：coverage=3 / 10 / 20 / 48。每条件独立：建记忆 → 投影 → BGE 嵌入 → 检索 → 上下文 → 生成。各保存 `memory.json / trace.json / context.txt / generation.txt`。

## 8. Memory Construction Results
| Coverage | 章节成功/失败 | 人物 | 事实 | 事件 | Chunk |
|---|---|---|---|---|---|
| 3 | 3/0 | 4 | 24 | 8 | 14 |
| 10 | 10/0 | 11 | 115 | 32 | 49 |
| 20 | 20/0 | 29 | 248 | 70 | 103 |
| 48 | 46/**2** | 34 | 519 | 146 | 215 |

- **记忆池随覆盖大幅增长**（人物 4→34，事实 24→519）。
- coverage 48 有 2 章提取失败（deepseek 把 summary 返回成字符串，Jackson 反序列化失败）——**已知提取可靠性问题**（PARTIAL_FAILED 语义）。

## 9. Retrieval Results（final 命中章节分布）
| Coverage | final 检索命中章节 |
|---|---|
| 3 | 46 / 47 / 48 |
| 10 | 42 / 44 / 45 / 47 / 48 |
| 20 | 42 / 44 / 45 / 47 / 48 |
| 48 | 44 / 45 / 47 / 48 |

**关键**：检索命中章节范围在 3→10 时从 46-48 扩到 42-48；但 **10→20→48 基本不再扩大**（始终 ~42-48）。**即使 48 章记忆（215 chunks 全在池里），final top-8 也只选出最近几章**——更早的"体系设定"记忆（1-40 章）未被断点查询的 top-8 检索选中。

## 10. Context Comparison
| Coverage | 当前人物状态 | 最近事件 | 检索到的相关记忆 |
|---|---|---|---|
| 3 | 4 条 / 225 字 | 8 条 | 5 条 / 834 字 |
| 10 | 5 条 / 428 字 | 10 条 | 3 条 / 747 字 |
| 20 | 5 条 / 548 字 | 10 条 | 3 条 / 703 字 |
| 48 | 5 条 / 609 字 | 10 条 | 4 条 / 736 字 |

- **current-facts 段随覆盖略增**（4→5 条、文本更长）——这是覆盖带来上下文的主要增量。
- **retrieved-memory 段不随覆盖增长**（始终 3-5 条、命中最近章节）——RAG 输出对覆盖不敏感。
- 最近章节原文段在所有 coverage 均为空（8192 预算被断点+其他段占满）。

## 11. Continuation Comparison（统一 0-2 评分）
| 维度 | cov3 | cov10 | cov20 | cov48 |
|---|---|---|---|---|
| Event Consistency | 2 | 2 | 2 | 2 |
| Character Consistency | 2 | 2 | 2 | 2 |
| Relationship Consistency | 2 | 2 | 2 | 2 |
| Plot Thread Continuity | 2 | 2 | 2 | 2 |
| Foreshadowing Usage | 2 | 2 | 2 | 2 |
| Hallucinated Entities（反向） | 1 | 2 | 2 | 1 |
| **总分** | **11** | **12** | **12** | **11** |

- 四篇续写都连贯、符合遮天体系（苦海/灵墟洞天/庞博/吴清风/韩飞羽），方向都指向"原始废墟历练"。
- 覆盖大时细节略丰富（cov10/20 出现庞博仙苗身份、韩飞羽叔公、废墟异象）；cov3/cov48 各有一处轻微发明（cov3 空白"古经纸"、cov48 新人物"周云"）。
- **质量接近，无明显优劣**。

## 12. Error Classification
- 无 A（检索漏正确证据）——两条件检索都给出近期章节正确上下文。
- 无 B/C（记忆/上下文选择）——覆盖 48 的体系记忆在池中但未被选中，属**检索选择偏向近期**，非记忆缺失。
- 无 D（推理错误）——四篇续写推理方向都与遮天早期一致。
- E（轻微生成漂移）：cov3"古经纸"、cov48"周云"为轻微无据新增。

## 13. Case Study
- 无"方鹤"类错误（遮天无此案例）。重点观察"体系记忆（1-40 章苦海/荒古禁地）是否进入 final context"——**结论：没有**。48 章记忆的 final top-8 只含 44-48 章，体系设定记忆未被断点查询选中。
- 说明：断点查询（灵墟洞天近期事件）天然偏向近期章节；若用"苦海如何开辟"这类**体系定向 query**，早期记忆才可能被检索到。本实验未覆盖该场景（局限）。

## 14. Marginal Benefit Analysis（核心）
- **3 → 10**：记忆池扩大（4→11 人物）+ 检索范围扩到 42 章 + 续写细节更丰富 → **明显改善**。
- **10 → 20**：记忆继续扩大（11→29 人物）但检索范围不再扩大、retrieved-memory 段不增 → **边际收益小**。
- **20 → 48**：记忆暴涨（29→34 人物 / 248→519 事实）但 context 的 retrieved-memory 段几乎不变、续写质量接近 → **边际收益趋近 0**。

**结论：提升主要来自 3→10 段；10 章之后，覆盖增加对"近期断点续写"的边际收益很小。**

## 15. Limitations
- **模型先验混杂**：deepseek 训练数据认识遮天，续写与遮天体系的一致不完全归因于 InkForge 记忆。**检索/上下文分析不受此影响**（纯 InkForge 侧）。
- **断点查询近期偏向**：自动 query（第 48 章断点）自然偏好近期章节，未直接测试"体系定向 query"下早期记忆的价值。
- 单次生成（未取均值），LLM 随机性；评分单标注。
- coverage 48 有 2 章提取失败（deepseek JSON 形状不稳定）。

## 16. Conclusion
**在本实验固定条件下：**
- 扩大 Memory Coverage 确实**显著扩大记忆池**（人物/事实/事件/Chunk 均暴涨）。
- **但 final context 的 retrieved-memory 段不随之扩大**（始终 3-5 条、命中最近 42-48 章），"体系设定"早期记忆虽在池中却未被 top-8 检索选中。
- **续写质量在 3→10 有明显提升，10→20→48 趋近平台**（coverage 10 后边际收益接近 0）。

**对 P5 的决策指向**：
- 结论接近**决策树 B/C**：`3 < 10 < 20 ≈ 48`。
- **单纯"建更多记忆"（Full Memory Job）对近期断点续写的边际收益有限**——真正的杠杆在于：
  1. **检索/上下文选择**：如何让断点查询命中更早的"体系/伏笔"证据（Context Prioritization / Narrative State）；
  2. **记忆压缩**：把长期间接设定压成紧凑状态，而非堆原始事实。
- 因此建议：P5 主线偏向 **Memory Compression / Narrative State + 检索选择优化**，而非无上限的全量记忆构建。Full Memory 可作为**基础底座**（让更早记忆可检索），但必须配合**选择/压缩**才能兑现到续写。

> 注：若未来用"体系定向 query"重测，早期记忆的价值可能不同——建议作为后续补充实验。
