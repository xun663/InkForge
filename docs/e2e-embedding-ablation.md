# InkForge Embedding A/B：MockEmbedding vs 真实 BGE-M3

## 1. Objective
在**严格控制变量**下测量真实 BGE-M3（1024 维语义向量）相对于 MockEmbedding（确定性 n-gram 伪向量）对 InkForge 检索与续写的独立贡献，并定位变化发生在哪一层（Embedding → Retrieval → Context → Generation）。

## 2. Controlled Variables（两实验共同固定）
| 变量 | 固定值 |
|---|---|
| 唯一自变量 | EmbeddingProvider：`MockEmbeddingProvider` vs 本地 BGE-M3（`BAAI/bge-m3`，1024d，CUDA，归一化） |
| BM25 | `LuceneBm25Retriever`（同 chunk 库，两条件共用，天然一致） |
| Fusion | `RrfFusion.fuse`，rrf-k=60，fusion-top-30 |
| Reranker | `PassThroughReranker`（top-8） |
| top-k | bm25/vector=30，rerank=8 |
| Query | 同一套（见下） |
| LLM / 生成参数 | deepseek-v4-flash · temperature 0.8 · 1200 tokens |
| Context Budget | 8192 tokens |
| Memory / Chunk | 同一批 MemoryChunk（仅投影一次，两条件复用） |

## 3. Retrieval Dataset
`BenchmarkNovelData`（蛊真人风格 12 章样本）· `BenchmarkQueries`（24 条人工标注 query + golds）· 内存由 fixture 注入（无 LLM 提取随机性）· 同一批 chunks 一次性投影。

## 4. Continuation Dataset
3 篇原创短篇（剑断长夜 / 雾港迷案 / 古卷残页）· Visible Prefix 建记忆一次（deepseek 提取，8/7/8 章全部成功）· Hidden Gold 结局绝不进入记忆/检索/prompt · 两条件复用同一批 chunks 与 query。

## 5. Experimental Conditions
- **A**：MockEmbeddingProvider
- **B**：本地 BGE-M3（`bge_m3_server.py`，OpenAI 兼容 `/v1/embeddings`）
- Retrieval A/B 用 BenchmarkNovelData；Continuation A/B 用 3 篇短篇。均在同一 JVM 内对同一批 chunks 分别嵌入、分别检索，**不重新提取/投影**。

## 6. Retrieval Results（chunk 口径，24 query 均值）
| Method | Mock R@5 | Mock R@10 | Mock MRR | Mock NDCG | Mock Use@8 | BGE R@5 | BGE R@10 | BGE MRR | BGE NDCG | BGE Use@8 |
|---|---|---|---|---|---|---|---|---|---|---|
| bm25 | 0.819 | 0.972 | 0.762 | 0.784 | 0.972 | 0.819 | 0.972 | 0.762 | 0.784 | 0.972 |
| vector | 0.819 | 0.972 | 0.733 | 0.777 | 0.972 | 0.847 | 0.972 | 0.709 | 0.761 | 0.910 |
| hybrid-rrf | 0.882 | 0.972 | 0.816 | 0.817 | 0.972 | 0.840 | 0.972 | 0.754 | 0.785 | 0.972 |
| final | 0.882 | 0.972 | 0.816 | 0.817 | 0.972 | 0.840 | 0.972 | 0.754 | 0.785 | 0.972 |
| multi-query | 0.736 | 0.896 | 0.699 | 0.713 | 0.847 | 0.771 | 0.889 | 0.675 | 0.699 | 0.840 |

- **BM25 两条件完全一致**（embedding 不影响 BM25）✅
- BGE 的 vector **R@5 略高**（0.847 vs 0.819），但 MRR/NDCG/Useful@8 略低。

## 7. Vector Ranking Comparison
- **vector 前 10 排序 24/24 全部变化**——BGE 确实改变了语义排序。
- 首个 gold 在 vector 中平均 rank：Mock=0.79，BGE=1.25（0 基）。
- BGE 提升 gold rank 5 条 / 恶化 7 条 / top-1 即精确 gold 平 13-13。
- 案例「方源与白凝冰初次相遇」：精确 gold `ch0 EVENT 青茅山初遇` **Mock 第 7 → BGE 第 1**（BGE 语义更准），但两者都在 top-10 → Recall 相同。

## 8. RRF / Final Comparison
- **Gold 命中集合：24/24 query 完全相同**；BGE 新增命中 gold = 0，Mock 独有 = 0。
- final 阶段命中集合不同 = 0/24。→ 在本数据集上 RRF+top-10 口径下，embedding 差异被稀释，未转化为 recall 差异。

## 9. Context Comparison（Continuation A/B）
3 篇短篇两条件 **final 检索集合全部不同**（story1 12 vs 12、story2 13 vs 11、story3 11 vs 11，均不同），context 的 `retrieved-memory` 段因此不同；其余段（断点/事实/事件/最近章节）完全相同。

- **story1 剑断长夜**：BGE context 带出 `ch5 陆沉深夜练剑/疑似禁术`（关键可疑证据）；Mock context 带出 `ch4 陆沉照拂`（正面）。→ BGE 把"陆沉可疑"证据送进上下文。
- **story2 雾港迷案**：Mock final 含何伯/守夜人，BGE 不含（但何伯本就在可见章节，两版续写都用到）。
- **story3 古卷残页**：两条件 final 都含老胡+陈福，均不含"守卫者"（属隐藏 gold）。

## 10. Continuation Comparison
统一 0-2 评分（人工单标注，0=差 1=中 2=好；Hallucinated 为反向指标）：

| 维度 | story1 Mock | story1 BGE | story2 Mock | story2 BGE | story3 Mock | story3 BGE |
|---|---|---|---|---|---|---|
| Event Consistency | 2 | 2 | 2 | 2 | 2 | 2 |
| Character Consistency | 2 | 2 | 2 | 2 | 2 | 2 |
| Relationship Consistency | 2 | 2 | 2 | 2 | 2 | 2 |
| Plot Thread Continuity | 2 | 2 | 2 | 2 | 2 | 2 |
| Foreshadowing Usage | 2 | 2 | 2 | 2 | 2 | 2 |
| Hallucinated Entities（反向） | 2 | 2 | 1 | 1 | 1 | 2 |
| **总分** | **12** | **12** | **11** | **11** | **11** | **12** |

说明：两条件续写**质量接近**。story2 各自有轻微发明（Mock 补何伯缉私队员来历、BGE 杜撰渔夫名"郑老栓"）；story3 BGE 落在"它们醒了"（贴近 gold 的古卷封印危险），Mock 杜撰"续命之法"支线。

## 11. Error Classification
- **story1（重点案例）**：两条件 final 检索**都含陆沉**；BGE context 还带出"陆沉禁术"证据。**两版续写都未再出现"方鹤"**（此前"方鹤"误归因源自**记忆覆盖不足**——只建末 3 章记忆，属 **B Memory Representation/Coverage**；全量 8 章记忆后消失，与 embedding 无关）。"陆沉即真凶"是隐藏 gold，两条件都无从得知 → 非检索/推理错误，属信息边界。
- **story2 / story3**：轻微实体发明 → **E Generation Error**（方向合理但细节漂移），两条件程度相当。

## 12. Case Studies
1. **剑断长夜 · 方鹤**：明确为记忆覆盖问题（B 类），非 embedding 差异。全量记忆后 mock/bge 都正确聚焦陆沉。
2. **剑断长夜 · BGE context 优势**：BGE 语义检索把"陆沉禁术"可疑证据送入上下文（Mock 给的是"陆沉照拂"正面证据）——这是 BGE 改善 Evidence Retrieval 的直接例子，但未改变生成结果（deepseek 都推断出"赵铁山非真凶"）。
3. **遮天（前序压力测试）**：40 章记忆 + BGE，检索 vector score 0.76（真语义），续写群像更全；与 3 章记忆版本相比提升明显，但该对比同时变了记忆量与 embedding，不归因于单变量。

## 13. Findings
1. **BGE-M3 确实改变 vector ranking**（24/24），并可能把精确 gold 排得更前（第 7→第 1）。
2. **但未转化为检索指标收益**：在 BenchmarkNovelData 上命中集合完全一致，Recall 平、MRR/NDCG 略低。
3. **最终 context 改变**（3 篇 retrieved-memory 段均不同），且有案例显示 BGE 把更相关的语义证据（陆沉禁术）送进上下文。
4. **续写质量无稳定差异**：deepseek 从两套 context 生成的结果质量接近，BGE 的优势被模型+丰富上下文吸收。
5. **InkForge 检索/生成瓶颈不在 embedding**：在记忆覆盖充分 + 模型能力足够时，两条件都产出连贯续写；真正的提升杠杆是**记忆覆盖广度**（全量 vs 窗口）与 LLM 能力，而非向量质量。

## 14. Limitations
- 检索数据集小（12 章）且 query 关键词对齐（方源/白凝冰/青茅山等实体名）——**语义 embedding 的优势恰好在同义/改写/跨章关联上，本数据集未覆盖**。
- 续写单次生成（未多跑取均值），LLM 有随机性；评分单标注（主观）。
- BGE 向量仅来自可见前缀记忆（未混入 gold）。
- 未测 LLM Reranker（本实验刻意排除，避免第二变量）。

## 15. Conclusion
**在本实验固定条件下：**
- BGE-M3 相对于 Mock 在 BenchmarkNovelData 上**未展现出检索指标优势**（Recall 平、MRR/NDCG 略低），但**改变了 vector ranking 并提升了部分 query 的精确 gold 排序**。
- 在 3 篇原创短篇上，BGE **改变了 final context**（retrieved-memory 段不同，个别案例带入更相关的语义证据），但**未观察到稳定的端到端续写质量收益**（两条件续写接近，均为高分连贯文本）。
- **结论**：检索层/上下文层的语义差异真实存在，但当前 LLM（deepseek-v4-flash）+ 充分记忆覆盖下，**该差异未稳定传导到生成质量**。InkForge 的下一步提升方向更可能在**记忆覆盖广度**与**检索 query 的语义化**，而非向量模型本身。

> 注：所有结论限定于本实验固定条件（12 章基准 / 6-8 章短篇 / deepseek-v4-flash / 单次生成）。语义 embedding 在更大语料、同义改写型 query 下可能显现不同结果，留待后续扩展实验。
