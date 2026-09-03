# InkForge 架构文档

> InkForge — Long-Form AI Story Continuation & Narrative Memory Engine
> 面向长文本生成的 AI 小说续写与叙事记忆系统

## 1. 项目定位

InkForge 不是"AI 小说生成器"。它解决的核心问题是：

> 一部长篇小说（数十万到数百万字）无法完整放入 LLM Context 时，
> AI 如何**记住**几百章以前的剧情、**检索**出当前真正相关的历史、
> 在有限 Context 下**决定**什么值得进入 Prompt，并**验证**生成内容与原著不冲突。

核心设计原则：**确定性逻辑负责结构，LLM 负责语义。**

| 确定性逻辑（程序） | LLM 负责（语义） |
|---|---|
| 章节切分、编码检测、Token 统计 | 摘要、人物抽取、事件抽取 |
| BM25 / 向量检索、Rerank 融合 | 语义相关判断 |
| Context 预算分配、截断 | 超预算时的记忆压缩（最后手段） |
| 时间线排序、数据持久化 | 续写生成、一致性判断 |

## 2. 目标 Pipeline（全 Phase 闭环）

```text
Novel
  ↓
Parser（编码检测 + 章节切分）
  ↓
Chapter
  ↓
Story Memory（Chapter / Entity / Event / World / Style）
  ↓
Hybrid Retrieval（BM25 + Vector + Entity/Event → RRF → Reranker）
  ↓
Context Budget Manager（优先级分配 + 截断）
  ↓
LLM Generation（SSE 流式）
  ↓
Consistency Checker（人物 / 时间线 / 世界观 / 关系 / 物品）
  ↓
User Selection（Ignore / Modify / Regenerate）
  ↓
Memory Update ──────────────→ Story Memory（闭环）
```

## 3. 目录结构

```text
InkForge/
├── backend/          # Spring Boot 4.1 (Java 21, Maven Wrapper)
├── frontend/         # Vite + React + TypeScript（独立工程）
├── docs/             # 架构与设计文档
├── README.md
└── LICENSE           # Apache-2.0
```

## 4. Backend 包结构（按领域组织）

```text
com.inkforge
├── novel/        小说生命周期：上传、解析、查询（NovelRepository 接口 + InMemory 实现）
├── chapter/      章节：编码检测、规则切分、中文数字解析
├── context/      断点分析、Prompt Context 构建（预算作为参数传入，不硬编码）
├── generation/   续写编排、GenerationLog（用量/成本/延迟）
├── provider/     LlmProvider 抽象（OpenAI 兼容 + Mock），P3 扩展 Embedding/Reranker
└── common/       TokenCounter、PromptCatalog、SSE 工具、全局异常处理
```

`memory/`、`retrieval/`、`consistency/` 包在对应 Phase 落地，扩展点见下节。

## 5. 后续 Phase 扩展点（Phase 1 预留，不提前实现）

| 扩展点 | 预留方式 | 落地 Phase |
|---|---|---|
| 持久化 | `NovelRepository` / `GenerationLogRepository` 接口 + InMemory 实现，P2/P3 换 JPA + PostgreSQL(pgvector) 上层零改动 | P2/P3 |
| Story Memory | `memory/` 包：ChapterSummary / Character(+Fact 时效性) / Event(+链路) / WorldSetting / StyleProfile；结构化表为 source of truth，`memory_chunk` 为统一检索单元 | P2 摘要+人物，P3 事件+世界观，P6 风格 |
| Hybrid Retrieval | `retrieval/` 包：BM25(PG FTS 起步，Lucene/ParadeDB 升级) + pgvector + Entity/Event 结构化查询 → RRF 融合 → RerankerProvider | P3 |
| Context Budget Manager | P1 的 `ContinuationContextBuilder` 为简化版；P4 演进为按优先级多段预算分配 + 动态压缩 | P4 |
| Consistency Checker | `consistency/` 包：CheckResult{issue, severity, confidence, evidence, sourceChapter, suggestedFix} + Ignore/Modify/Regenerate 处理 | P5 |
| 多分支生成 / 时间线 / 事件图 | `generation/` 分支实体 + `event_link` DAG | P6 |
| Benchmark | 断点数据集 + Recall@K / 一致性指标 + Baseline vs Hybrid 对比 | P7 |

## 6. SSE 协议（Phase 1）

```
POST /api/novels/{id}/continuations
  event: token    data: "<JSON 编码的增量文本>"
  event: done     data: {"generationId": "...", "provider": "...", "model": "...",
                         "promptTokens": 123, "completionTokens": 456, "totalTokens": 579,
                         "latencyMs": 8400, "estimatedCostUsd": 0.0123}
  event: error    data: {"message": "..."}
```

- 每次生成拥有唯一 `generationId`（UUID），用于与 `GenerationLog` 关联
- 所有 data 均为 JSON 编码，避免增量文本中的换行破坏 SSE 协议
- `estimatedCostUsd` 由配置中的价格表（每 1M tokens）计算，仅作估算展示

## 7. 配置与密钥

- 默认 provider 为 `mock`：**不配置任何 API Key 即可完成完整 Phase 1 闭环**
- API Key 只允许来自环境变量 `INKFORGE_LLM_API_KEY`，绝不进入源码、配置文件或数据库
- Provider 通过 `INKFORGE_LLM_PROVIDER` 切换：`mock` / `deepseek` / `openai` / `ollama` / `openai-compatible`

## 8. Phase 1 已知限制（有意为之）

- 存储为进程内内存（HashMap），重启即失：接口隔离，P2 迁移 JPA 无痛
- Context 构建为"末章优先 + 向前滚动窗口"的确定性简化版：完整 ContextBudgetManager 在 P4
- 断点分析为确定性文本分析（末章 + 尾部摘录）：剧情语义理解在后续 Phase 引入
- Token 预算用 JTokkit(cl100k_base) 预估：最终以 Provider 返回的 usage 为准
