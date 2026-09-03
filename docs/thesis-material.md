# InkForge Technical / Thesis Material

> 技术材料说明：本文件为未来论文/技术报告可直接改写的材料沉淀，**不代表项目定位为论文项目**——InkForge 仍是一个开源的长篇小说续写与叙事记忆工具。所有描述基于当前真实实现（P1-P3 已封版），未实现能力一律标注为"未来方向"。

---

## 1. Project Background

长篇网络小说通常有数十万到数百万字、数百到数千章，无法整体放入 LLM Context。直接续写时，LLM 只能看到"最近几章"：

- 几百章前的人物设定、境界、物品状态会"失忆"；
- 关键历史事件无法被当前续写场景引用；
- 当前人物状态与历史状态容易混淆；
- 生成内容可能与原著设定冲突（一致性验证留待后续阶段）。

InkForge 的目标是把"长篇小说续写"从"上下文截断 + 生成"升级为"叙事记忆构建 + 检索增强生成"（Narrative Memory & Retrieval-Augmented Generation）。

## 2. System Goals

| 目标 | 说明 |
|---|---|
| Story Memory | 将小说章节转化为结构化长期记忆：章节摘要、人物事实（含生命周期）、剧情事件 |
| Hybrid Retrieval | 在记忆规模远超 Context 时，用 BM25 + 向量检索 + RRF 融合找回当前真正相关的历史 |
| Context Integration | 在严格 Token Budget 下按优先级将"原文 + 记忆 + 检索结果"组装进 Prompt |
| Observability | 记录检索全过程（Retrieval Trace），让"为什么参考这些历史"可解释、可调试 |
| 可替换 Provider | LLM / Embedding / Reranker 全部接口化，不绑定任何单一厂商 |
| 可持久化部署 | PostgreSQL + pgvector 可选持久化；默认 InMemory + Mock 零依赖可运行 |

设计原则：**确定性逻辑负责结构（提取校验、状态合并、检索排序、预算分配），LLM 只负责语义（摘要、事实提取、续写、可选重排）**。

## 3. Overall Architecture

### 3.1 续写链路（检索增强生成）

```text
User
  ↓
Frontend
  ↓
Continuation API (SSE)
  ↓
Context Builder
  ├── Recent Chapters（断点原文窗口）
  ├── Current Facts（当前人物状态，直查 Story Memory，不检索）
  └── Retrieved Memory（检索结果 section）
        ↓
    RetrievedMemoryProvider
        ↓
    RetrievalQueryBuilder（Primary / Character / Thread，最多 3 查询）
        ↓
    MultiQuery（每查询独立 Hybrid，按 chunkId 取最高分合并）
        ↓
    Hybrid Retrieval
      ├── BM25（Lucene + SmartChineseAnalyzer）
      └── Vector（Mock / pgvector 余弦）
          ↓
        RRF Fusion（k=60）
          ↓
        Reranker（默认 PassThrough；可选 LlmListwise）
          ↓
      RetrievalTrace（记录各阶段结果，可查可解释）
          ↓
      Context Assembly（ContextSection 分区预算）
          ↓
        LLM Provider（OpenAI 兼容 / Mock）
          ↓
      SSE / DoneMeta（retrievedCount / retrievalTraceId）
```

### 3.2 记忆构建链路（Story Memory 写入）

```text
Novel / Chapter（TXT 解析 → 规则章节切分 → 编码检测）
  ↓
Memory Extraction（单次 LLM 结构化输出：摘要 + 人物 + 事实 + 事件）
  ↓ 严格 JSON 校验 + 引用子串校验 + 有限重试
Story Memory（Source of Truth）
  ├── ChapterSummary（章节摘要 + 未解决线索）
  ├── CharacterFact（CURRENT / SUPERSEDED / UNCERTAIN 三态生命周期）
  └── StoryEvent（剧情事件）
  ↓
MemoryChunk Projection（确定性、幂等；CURRENT 事实不投影）
  ↓
Embedding（可选；内容哈希幂等）
  ↓
BM25 索引（可重建缓存） / Vector 存储
```

## 4. Core Data Model

| 实体 | 职责 | 关键设计 |
|---|---|---|
| `Novel` / `Chapter` | 小说与章节（不可变 record，`(novelId, ordinal)` 身份键） | 与 JPA 实体分离，领域层零持久化污染 |
| `ChapterSummary` | 章节结构化记忆（摘要/关键事件/人物/地点/物品/未解决线索） | 纯故事内容；提取过程观测独立于 `MemoryExtractionRecord` |
| `CharacterFact` | 原子事实，带生命周期：`CURRENT`（当前状态）/ `SUPERSEDED`（历史，记录 validFrom/validUntil）/ `UNCERTAIN`（传闻，独立保留） | **事实键**：普通 `(character, category, attribute)`；关系 `(character, RELATIONSHIP, attribute, targetCharacter)`——多目标关系共存不互覆；当前状态是查询视图而非独立数据 |
| `StoryEvent` | 剧情事件（参与者/地点/结果/重要性/来源引用） | 无事件链（Event Graph 留 P6） |
| `MemoryChunk` | Story Memory 的**检索投影**（SUMMARY/FACT/EVENT），非事实来源 | 确定性 id（幂等）；**CURRENT 事实永不进入检索** |
| `RetrievalTrace` | 观测对象：queries + 各阶段结果（bm25/vector/fusion/rerank/final） | 独立于 Story Memory，独立存储 |

## 5. Retrieval Design

### 5.1 BM25（Lucene + smartcn）
- 中文网文需要真实分词——PG 默认 tsvector 对中文会退化为整句 token（假 BM25），故选择 Lucene 9.12.3 + SmartChineseAnalyzer，默认 k1/b（未调参）。
- 索引策略：**数据库是事实源，Lucene 是可重建缓存**——per-novel 内存索引（ByteBuffersDirectory），首查懒建，chunk revision 变化自动重建，重启丢失后重建；跨小说隔离是结构性保证。

### 5.2 Vector Retrieval
- `EmbeddingProvider` 抽象：`MockEmbeddingProvider`（确定性 n-gram 伪向量，零 Key 管线验证用）+ OpenAI-compatible 真实 Provider（模型/维度/base-url 配置化）。
- 检索实现：InMemory 暴力余弦（默认）与 PostgreSQL pgvector（`<=>` 余弦距离转相似度，HNSW `vector_cosine_ops` 索引，JdbcTemplate 原生 SQL 隔离 ORM 风险）。
- **维度不变量**：配置 = Mock 维度 = 真实 Provider 返回维度 = 数据库 `vector(N)`，四处校验，不一致明确失败（不截断/填充）。

### 5.3 RRF Fusion
`RRF(d) = Σ 1 / (k + rank(d))`，rank 从 1 开始，k 默认 60；同一 chunk 跨通道去重并累加分数；纯函数、无 LLM、可单测（含手工数值断言）。

### 5.4 Reranker
- `PassThroughReranker`（默认）：按融合顺序 top-K 截断，确定性、零成本；
- `LlmListwiseReranker`（可选）：编号协议（候选 ≤15、单条 ≤200 字符），LLM 返回编号数组后映射回原结果；协议违规 → `RerankException` → Hybrid 降级到融合排名。
- **注意**：默认配置不执行语义重排（见实验结论）。

### 5.5 MultiQuery
`RetrievalQueryBuilder`（确定性，纯 Java）最多生成 3 个查询：Primary（断点尾部+摘要）/ Character（当前人物）/ Thread（未解决线索）；每个查询独立跑完整 Hybrid 管线，按 chunkId 取最高分合并。当前融合策略在 benchmark 中出现候选稀释（见实验结论）。

## 6. Memory Retrieval Lifecycle（从提取到 Context 的完整生命周期）

```text
章节提取（窗口模式，显式触发）
  → 结构化校验（结构/枚举/范围/引用子串）→ 有限重试 → FAILED 降级不阻断
  → MemoryUpdateService 确定性合并（CREATE/UPDATE/SUPERSEDE/IGNORE/UNCERTAIN）
  → 投影为 MemoryChunk（幂等）
  → 生成 Embedding（内容哈希幂等，失败不阻断）
  → 续写时：QueryBuilder → MultiQuery → Hybrid → RRF → Reranker
  → 检索结果进 retrieved-memory section（预算约束）
  → 生成 → SSE done（携带 retrievedCount / retrievalTraceId）
```

## 7. Fault Tolerance（容错设计）

| 失败场景 | 行为 |
|---|---|
| Embedding 失败 | warn 日志；Story Memory 提取/投影不受影响 |
| Retrieval 任何异常 | retrieved-memory section 为空，续写走 P2 记忆路径 |
| Reranker 失败 | 回退 RRF 融合排名 |
| Trace 保存失败 | 只 warn，正文生成不受影响 |
| 无记忆 / 无检索结果 | 自动降级到最近章节窗口（P1 路径） |
| 默认运行 | InMemory + Mock + 零 API Key + 零 Docker 可完整运行 |

原则：**增强层失败绝不阻断基础续写**（P1 是最终兜底，P2 是记忆增强，P3 是检索增强）。

## 8. Observability（Retrieval Trace 与前端展示）

- 每次续写检索记录 `RetrievalTrace`：queries + 各阶段（bm25/vector/fusion/rerank/final）完整结果，与 generationId 关联；
- 查询 API：`GET /api/novels/{id}/retrieval-traces/{traceId}`（novel 隔离，不跨小说泄露）；
- SSE done 事件携带 `retrievedCount` / `retrievalTraceId`；
- 前端 Trace Panel：显示"参考了 X 条记忆"，可展开查看每个查询与每个阶段的结果（阶段可折叠、局部 Loading/Error/Empty、缓存避免重复请求）。

## 9. Experimental Design

完整实验设计与数据见 [benchmark-results.md](benchmark-results.md)。要点：

- 固定 12 章中文网文 fixture，24 条人工标注 Query（gold = chapterOrdinal + memoryType + sourceId 语义描述 + helpfulness）；
- 双口径：chunk（章节+类型双匹配）/ chapter（仅章节）；
- 指标：Recall@5/10、MRR@10、NDCG@10、Useful@8；
- 固定条件：Lucene 9.12.3(smartcn) · MockEmbedding 1024 维 · RRF k=60 · top-30/30/30/8 · PassThrough Reranker · Mock provider（零 Key 零 Docker）；
- 8 组消融：baseline / p2-memory / bm25 / vector / hybrid-concat / hybrid-rrf / rrf-rerank / multi-query；
- **Memory 数据人工构造**：实验评估的是投影、Embedding、Retrieval 与 Ranking，而非提取模型质量（Mock 提取对所有章节输出同一模板）。

## 10. Ablation Results

直接引用 [benchmark-results.md](benchmark-results.md) §9 的真实数据（chunk/chapter 双口径）：

| Method | Recall@5 | Recall@10 | MRR@10 | NDCG@10 | Useful@8 |
|---|---|---|---|---|---|
| baseline | 0.090 / 0.257 | 0.090 / 0.257 | 0.104 / 0.257 | 0.074 / 0.209 | 0.090 |
| p2-memory | 0.271 / 0.438 | 0.271 / 0.438 | 0.152 / 0.305 | 0.151 / 0.285 | 0.271 |
| bm25 | 0.819 / 0.944 | 0.972 / 0.958 | 0.762 / 1.000 | 0.784 / 0.943 | 0.972 |
| vector | 0.819 / 0.944 | 0.972 / 0.958 | 0.733 / 0.958 | 0.777 / 0.930 | 0.972 |
| hybrid-concat | 0.819 / 0.944 | 0.972 / 0.958 | 0.762 / 1.000 | 0.784 / 0.943 | 0.972 |
| hybrid-rrf | 0.882 / 0.944 | 0.972 / 0.958 | 0.816 / 1.000 | 0.817 / 0.947 | 0.972 |
| rrf-rerank | 0.882 / 0.944 | 0.972 / 0.958 | 0.816 / 1.000 | 0.817 / 0.947 | 0.972 |
| multi-query | 0.715 / 0.840 | 0.826 / 0.944 | 0.629 / 0.865 | 0.642 / 0.837 | 0.826 |

## 11. Experimental Findings

1. **检索显著优于无检索基线**：Recall@10(chunk) 从 baseline 0.090 / p2-memory 0.271 提升到检索方法 0.972——检索层是"找回远期记忆"的必要组件。
2. **RRF 在当前 benchmark 有收益**：Recall@5 0.819→0.882、MRR@10 0.762→0.816、NDCG@10 0.784→0.817；表述限定为"本次固定 benchmark 上观察到的稳定增益"。
3. **concat 无额外收益**：简单拼接 ≈ BM25（vector 贡献被覆盖）——支持"基于排名的融合优于拼接"的工程观察。
4. **PassThrough Reranker 未产生额外收益**：仅 top-K 截断、无语义重排；真实 LLM Reranker 收益未验证（不是"Reranker 无价值"）。
5. **MultiQuery 当前配置未产生收益**：多查询合并出现候选稀释（Recall@5 0.882→0.715）；如实记录为融合策略问题而非 MultiQuery 概念无效。
6. **Mock Embedding 限制语义结论**：Vector 与 BM25 接近源于 n-gram 词面倾向，不能外推真实语义向量。

## 12. Limitations

- benchmark 规模有限（单小说 12 章 / 24 条 Query）；
- Story Memory 人工构造，未评估提取质量；
- Mock Embedding 非真实语义向量；
- 未验证真实 LLM Reranker；
- Docker unavailable：PostgreSQL/pgvector 与 Trace 集成测试当前 skipped（本机未安装 Docker；IT 已编写，安装 Docker 后即可执行）；
- 当前 MultiQuery 融合策略仍有优化空间。

## 13. Engineering Trade-offs

| 决策 | 理由 |
|---|---|
| 数据库（事实源）vs Lucene（可重建缓存） | 结构化数据可追溯、可迁移；内存索引按需重建，避免持久化索引的一致性复杂度；revision 机制保证缓存自愈 |
| 独立端口 + Profile（InMemory / postgres） | 默认零依赖可运行；持久化按需启用；上层业务不感知具体实现 |
| JPA 与 JdbcTemplate 分工 | 结构化表用 JPA；pgvector 向量操作用原生 SQL（隔离 ORM 向量映射风险） |
| 领域 record 与 JPA Entity 分离 | 领域层零注解污染、不可变、可单测 |
| CURRENT 事实不检索 | 当前状态永远直查，杜绝"检索出过时事实让 LLM 猜测"的错误 |
| Provider 全接口化 | LLM / Embedding / Reranker 可替换；Mock 实现保证零 Key 全链路可测 |

## 14. Future Work（未来可能方向，未实现）

- 真实 Embedding（如 bge-m3）下的 Benchmark 复测，验证向量检索的语义差异化价值；
- 真实 LLM Reranker（LlmListwiseReranker）的效果评估；
- 更大规模测试集（多部小说、更多标注 Query）；
- MultiQuery 融合策略优化（加权 / 按类型聚合 / 去稀释）；
- PostgreSQL/pgvector 与 Trace 集成测试在本机 Docker 环境下完整执行；
- 用户侧更丰富的记忆管理（记忆浏览、编辑、删除、指定范围重建）。
