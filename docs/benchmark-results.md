# Benchmark Results — InkForge P3-G 检索消融实验

> 生成：2026-08-17 · 数据源：`RetrievalBenchmarkTest`（真实运行输出，未做任何调整）
> 可复现命令（仓库真实存在）：`cd backend && ./mvnw test -Dtest=RetrievalBenchmarkTest`
> 原始输出亦写入 `backend/target/benchmark-results.md`（构建产物，与本报告一致）。

---

## 1. Overview

InkForge 的检索层（P3）回答一个问题：当小说远超 LLM Context 时，如何从大量历史记忆中找到当前续写真正需要的信息。本实验以固定测试集、固定参数、零 API Key / 零 Docker 条件下，对 8 组检索方法进行消融对比，评估各组方法的召回与排序质量。

## 2. Evaluation Goals

1. 验证"检索层"相对"无检索路径"（断点附近窗口 / P2 记忆窗口）的必要性；
2. 对比单路 BM25、单路 Vector 与 Hybrid 融合方法的差异；
3. 检验 RRF 融合相对"简单拼接"的实际增益；
4. 记录 Reranker（默认 PassThrough 实现）与 MultiQuery 在当前配置下的真实表现；
5. 为后续真实 Embedding / 真实 Reranker 实验提供可复现基线。

## 3. Dataset

- 测试小说：`backend/src/test/resources/fixtures/benchmark_novel.txt` —— 12 章中文网文（约 3 千字），内容固定不变。
- 情节要素覆盖：人物（方源 / 白凝冰 / 血手魔尊）、地点（青茅山 / 北原 / 狐仙福地 / 玄冰洞 / 天机阁）、物品（蛊虫 / 九叶灵芝）、境界演进（三转 → 四转 → 五转 → 六转）、事件（初遇 / 结盟 / 追杀 / 反目 / 大婚伏杀）。

## 4. Annotation

- 24 条人工标注 Query（`benchmark/BenchmarkQueries.java`），贴近真实续写场景的查询表达（人物、地点、事件、境界、关系、结局等）。
- 每条 Query 标注：
  - `golds`：1-3 个 gold，每个包含 `chapterOrdinal` + `memoryType`（SUMMARY / FACT / EVENT）+ `sourceId`（语义描述，用于人工核查）；
  - `helpfulness`：0 = 无帮助，1 = 有一定帮助，2 = 很有帮助。
- **Memory 数据说明**：测试小说的 Story Memory 由标注作者手工构造（与章节内容一一对应），而非依赖 Mock 提取结果——Mock 提取对所有章节输出同一模板，会破坏检索区分度。**因此本实验评估的是投影、Embedding、Retrieval 与 Ranking，而不是 Memory Extraction 模型质量。**

## 5. Evaluation Protocol

- 每条 Query 分别用 8 组方法检索，得到 top-K 结果列表；
- 命中判定双口径：
  - **chunk 口径**：`result.memoryType == gold.memoryType` 且 `chapterOrdinal` 匹配；
  - **chapter 口径**：仅 `chapterOrdinal` 匹配。
- 指标按"gold 去重计数"（同一 gold 被多个结果命中只计一次），最终所有指标 ≤ 1。

## 6. Experimental Environment

| 项 | 值 |
|---|---|
| 语言 / 框架 | Java 21 · Spring Boot 4.1 |
| BM25 | Lucene 9.12.3 + SmartChineseAnalyzer（中文分词），默认 k1/b，未调参 |
| Vector | MockEmbeddingProvider（1024 维确定性 n-gram 多探针特征向量 + L2 归一化）+ InMemory 暴力余弦 |
| RRF | k = 60（标准值） |
| Reranker | PassThroughReranker（默认配置） |
| 检索参数 | bm25-top-30 / vector-top-30 / fusion-top-30 / rerank-top-8 |
| 模型条件 | Mock provider（零 API Key、零 Docker） |

**Mock Embedding 能力边界（必须明确）**：MockEmbeddingProvider 的 n-gram 特征设计本身具有**词面匹配倾向**，用于测试管线、零 Key 演示与流程验证，**不代表真实语义 Embedding 质量**。因此本实验中 Vector 与 BM25 表现接近，**不能据此证明真实语义向量与 BM25 等价**；真实 Embedding（如 bge-m3）的差异化效果需要真实模型重新评估。

## 7. Ablation Groups

| 组 | 定义 |
|---|---|
| baseline | 最近 3 章原文窗口（无检索；Recall 反映"断点附近覆盖度"） |
| p2-memory | 最近 3 章摘要 + 最近事件候选（无检索，P2 记忆窗口） |
| bm25 | 单 BM25（Lucene + smartcn），top-10 |
| vector | 单 Vector（余弦相似度），top-10 |
| hybrid-concat | BM25 top-30 + Vector top-30 **合并去重**（先 BM25 后 Vector，不做分数融合），top-10 |
| hybrid-rrf | BM25 + Vector → RRF(k=60)，top-10 |
| rrf-rerank | RRF → PassThroughReranker（默认配置，top-8 截断） |
| multi-query | 标注 Query + 固定人物 Query + Thread Query 三路完整管线，按 chunkId 取最高分合并，top-10 |

## 8. Metrics

| 指标 | 定义 |
|---|---|
| Recall@K | top-K 内命中的 gold 数 / gold 总数（gold 去重计数） |
| MRR@10 | 首个命中 gold 的倒数排名（未命中为 0） |
| NDCG@10 | 命中即 rel=1 的 DCG/IDCG |
| Useful@8 | top-8 内命中 gold 数 / min(gold 数, 8)——"有用记忆的即时覆盖率" |

表中 "a / b"：**a = chunk 口径，b = chapter 口径**。

## 9. Results

| Method | Recall@5 (chunk/ch) | Recall@10 (chunk/ch) | MRR@10 (chunk/ch) | NDCG@10 (chunk/ch) | Useful@8 |
|---|---|---|---|---|---|
| baseline | 0.090 / 0.257 | 0.090 / 0.257 | 0.104 / 0.257 | 0.074 / 0.209 | 0.090 |
| p2-memory | 0.271 / 0.438 | 0.271 / 0.438 | 0.152 / 0.305 | 0.151 / 0.285 | 0.271 |
| bm25 | 0.819 / 0.944 | 0.972 / 0.958 | 0.762 / 1.000 | 0.784 / 0.943 | 0.972 |
| vector | 0.819 / 0.944 | 0.972 / 0.958 | 0.733 / 0.958 | 0.777 / 0.930 | 0.972 |
| hybrid-concat | 0.819 / 0.944 | 0.972 / 0.958 | 0.762 / 1.000 | 0.784 / 0.943 | 0.972 |
| **hybrid-rrf** | **0.882** / 0.944 | 0.972 / 0.958 | **0.816** / 1.000 | **0.817** / 0.947 | 0.972 |
| rrf-rerank | 0.882 / 0.944 | 0.972 / 0.958 | 0.816 / 1.000 | 0.817 / 0.947 | 0.972 |
| multi-query | 0.715 / 0.840 | 0.826 / 0.944 | 0.629 / 0.865 | 0.642 / 0.837 | 0.826 |

## 10. Result Analysis

### 10.1 Retrieval necessity（检索是硬需求）
Recall@10(chunk) 从 baseline 的 **0.090** 提升到 BM25/Vector 的 **0.972**，P2-Memory 窗口为 **0.271**。相比无检索路径，检索系统在本 benchmark 上表现出**明显优势**——仅依赖断点附近窗口或短期记忆窗口不足以覆盖远期相关信息，这是"为什么需要检索层"的直接实验依据。

### 10.2 BM25 vs Vector
单路 BM25 与单路 Vector 在多数指标上接近（Recall@5 均为 0.819；MRR 0.762 vs 0.733）。**在 Mock Embedding（词面倾向）条件下**，二者差异有限；真实语义模型下需要重新评估（见 Limitations）。

### 10.3 RRF
在本次固定 benchmark 上观察到 **RRF 的稳定增益**（相对单路 BM25）：
- Recall@5(chunk)：0.819 → **0.882**
- MRR@10(chunk)：0.762 → **0.816**
- NDCG@10(chunk)：0.784 → **0.817**

表述限定：这是"在本次固定 benchmark 上观察到的增益"，**不写成"RRF 必然提升"**。

### 10.4 Simple Concatenation（简单拼接无效）
hybrid-concat 与 BM25 指标基本一致（vector 结果被 bm25 top-10 完全覆盖，未产生贡献）。本实验支持**"简单结果拼接不能替代基于排名的融合"**这一工程观察；不扩大为普遍理论结论。

### 10.5 Reranker limitation
rrf-rerank 与 hybrid-rrf 完全一致。原因是实验使用 **PassThroughReranker**——它只按融合顺序执行 top-K 截断，**没有真正进行语义重排**。因此：
> 本次实验没有验证真实 LLM Reranker（LlmListwiseReranker）的收益。

**不能写成"Reranker 没有价值"**——只说明默认 PassThrough 实现不引入重排能力；真实 LLM Reranker 的效果需要在真实模型条件下进一步验证。

### 10.6 MultiQuery limitation
multi-query 在本 benchmark 上反而下降：Recall@5(chunk) = 0.715，Recall@10(chunk) = 0.826，MRR@10(chunk) = 0.629。当前实现为多查询独立检索后按 chunkId 取最高分并截断，在当前 benchmark 中多查询结果融合后出现**候选稀释**。如实记录：
> MultiQuery 在当前数据集和当前融合策略下未产生收益，甚至降低了部分指标。

不强行解释为 MultiQuery 本身无效——其价值场景（跨主题长程记忆）需更大测试集与更优融合策略验证（见 Future Work）。

### 10.7 Mock Embedding limitation
MockEmbedding 与 BM25 表现接近，必须注明其 n-gram 特征设计本身具有**词面匹配倾向**。当前 benchmark **不能证明**真实语义 Embedding 的最终效果——这是**实验限制**，不是实现错误。

## 11. Benchmark Correction History（实验修正记录）

首次运行实验时发现 **Metrics 缺陷**：同一 gold 被多个结果重复计数，导致 Recall 超过 1（chapter 口径出现 2.13 的异常值）。修复为"gold 去重计数"（`matched` 集合）后完整复跑，最终所有指标 ≤ 1。

该记录保留以体现 benchmark 的可审计性：实验过程包含基本的数据校验，而非只挑选最终结果。

## 12. Reproducibility

```bash
cd backend
./mvnw test -Dtest=RetrievalBenchmarkTest
```

- 输出：控制台汇总表 + `target/benchmark-results.md`（与本报告 §9 一致）。
- 固定条件：固定小说 fixture、固定 24 条标注、固定配置（application.yml 默认检索参数）、固定 Mock provider——结果可复现、可比对。
- 生产检索实现零修改；实验只消费生产组件（投影、Embedding、BM25、Vector、RRF、Reranker、MultiQuery）。

## 13. Limitations

1. **测试集规模有限**：单部 12 章小说、24 条 Query，结论外推需谨慎；
2. **Memory 人工构造**：Story Memory 由标注作者手工构造，未评估提取模型（Mock 提取对所有章节输出同一模板，会破坏检索区分度）；
3. **Mock Embedding**：n-gram 词面倾向，不能代表真实语义向量质量；
4. **未验证真实 LLM Reranker**：默认 PassThrough 只截断不重排；
5. **MultiQuery 融合策略**：当前"取最高分"策略导致候选稀释，仍有优化空间；
6. **Docker unavailable**：PostgreSQL/pgvector 与 Trace 的集成测试当前为 skipped（本机未安装 Docker），本实验在 InMemory + Mock 路径运行。

## 14. Conclusion

- 检索层相对无检索路径在本 benchmark 上带来显著召回提升（Recall@10(chunk) 0.090 → 0.972）；
- RRF 融合相对单路与简单拼接存在可观测、可复现的增益（Recall@5 +7.7%，MRR@10 +7.1%，NDCG@10 +4.2%）；
- 简单拼接、PassThrough Reranker、当前 MultiQuery 策略未产生额外收益——均如实记录，不做美化；
- 上述结论全部限定在"固定测试集 + Mock Embedding + PassThrough Reranker"的实验条件下。
