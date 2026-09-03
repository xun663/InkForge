# P5-A Full Memory Foundation

## 1. 当前问题
InkForge 的记忆构建只覆盖最近 `extract-window=3` 章（`extractRecent`）。对 1800+ 章长篇小说，一次只能建最后 3 章记忆，不是真正的"全书记忆"。用户必须反复触发窗口提取，且无法中途暂停/恢复/只重试失败章节。

## 2. P5-0 / P5-0.5 依据
- **P5-0（Coverage 3/10/20/48）**：断点续写 Query 偏近期，retrieved-memory 段不随覆盖增长。
- **P5-0.5（体系定向 Query）**：覆盖增大后**确实能检索出更多早期证据**（D1/D3/C3/B2/A1 提升明显），但覆盖 48 下关系/伏笔类（B1/C1/C2）仍"存了选不出"（A Retrieval Error）。
- **结论**：Full Memory（解决"没存够"）+ Query-aware Retrieval（解决"存了选不出"）是主线。**本阶段只实现 P5-A Full Memory Foundation**，Retrieval 优化留 P5-B。

## 3. Full Memory 架构
```
MemoryBuildJob（生命周期/进度/失败序数）
  ↓ 按 chapter ordinal 升序遍历
跳过 SUCCESS（Ground Truth = MemoryExtractionRecord）
  ↓ 未处理章节
StoryMemoryService.buildChapter（复用现有单章管线）
  extract → apply → project → embed → save record(SUCCESS/FAILED)
  ↓
更新 Job 进度 → 下一章 → 完成 / PARTIAL_FAILED
```
- 原 `extractRecent`（recent-window 快速构建）**保留**，`buildChapter` 单章逻辑两种模式共用。
- P2/P3 记忆与检索核心**零改动**。

## 4. MemoryBuildJob 状态机
```
PENDING → RUNNING
RUNNING → PAUSED / COMPLETED / PARTIAL_FAILED / CANCELLED
PAUSED  → RUNNING / CANCELLED
PARTIAL_FAILED → RUNNING（retry-failed）
```
非法转换抛 `IllegalStateException`（有测试锁定）。

## 5. Idempotency 设计
- **SUCCESS Record 是"章节记忆已成功提交"的事实边界**：runner 对已有 SUCCESS 的章节跳过，绝不重复 apply（Event 是 append-only 语义，重复 apply 会重复事件）。
- projection（`replaceForChapter`）与 embedding（contentHash）本已幂等，复用。
- 跳过章节也计成功（`recordChapter(true)`），保证 resume 后进度正确。

## 6. Resume 设计
- Ground Truth = `MemoryExtractionRecord`（SUCCESS），比 Job currentOrdinal 更可靠。
- resume 后 runner 从第 0 章开始遍历，SUCCESS 全部跳过（O(n) 记录检查，无 LLM 调用），只处理未成功章节。

## 7. Failure / Retry 设计
- 单章失败：记 FAILED record + 加入 failedOrdinals + 继续下一章 → 最终 PARTIAL_FAILED。
- `retry-failed`：只重跑 failedOrdinals（不重扫全书 LLM）；成功者移出 failed；全成功 → COMPLETED。

## 8. Persistence
- `memory_build_job` 表（Flyway V4）：job_id / novel_id / status / total/success/failed / current_ordinal / failed_ordinals(JSONB) / created/updated_at。
- 部分唯一索引 `uq_memory_build_job_active_novel (novel_id) WHERE status IN ('PENDING','RUNNING')` → DB 级并发保护。
- 章节级事实仍由 `memory_extraction_record` 承担；职责清晰不混淆。
- **InMemory**：Job 重启即失（开发/Demo，诚实边界）。

## 9. API
```
POST /api/novels/{novelId}/memory/build            → 启动（202，返回 Job RUNNING）
GET  /api/novels/{novelId}/memory/build            → 当前 Job（前端轮询）
GET  /api/novels/{novelId}/memory/build/{jobId}
POST /api/novels/{novelId}/memory/build/{jobId}/pause
POST /api/novels/{novelId}/memory/build/{jobId}/resume
POST /api/novels/{novelId}/memory/build/{jobId}/cancel
POST /api/novels/{novelId}/memory/build/{jobId}/retry-failed
```

## 10. Frontend
Memory Center 新增 **MemoryBuildPanel**：总章节/成功/失败/当前章节/状态 + 进度条；开始/暂停/继续/取消/重试失败按钮。活跃 Job 每 1.5s 轮询；页面刷新后从 `GET /build` 恢复状态。Build 错误是局部错误，不影响 Memory Center 其他内容。

## 11. 测试结果
`./mvnw test` = **249 tests / 0 failures / 0 errors / 10 skipped（Docker IT）**。
新增 P5-A：
- `MemoryBuildJobTest`（10）：状态机 + 非法转换 + 严格升序 + SUCCESS 跳过 + 单失败不阻塞 + retry-only-failed + 协作暂停 + 取消不可恢复 + 并发唯一 + **重复 Build 不重复 Event**（真实 MockLlmProvider 管线跑两次）。
- `MemoryBuildStressTest`（3）：100/500/1000 章 Mock 压测。

## 12. 压力测试结果（Mock 管线，无真实 LLM）
| 章节 | 耗时 | 每章 | Event 数 |
|---|---|---|---|
| 100 | 105ms | 1.05ms | 100 |
| 500 | 512ms | 1.02ms | 500 |
| 1000 | 1790ms | 1.79ms | 1000 |

- Job 全部 COMPLETED，successChapters=N，failed=0。
- **Event 精确 = N（无重复）**；人物因同主角去重为 2（符合确定性合并）。
- InMemory 1000 章无明显内存压力；真实 1800 章小说（含前言）亦按此管线处理。

## 13. 已知限制
- InMemory 模式重启即失 Job（PostgreSQL 才可恢复）。
- pause/cancel 是协作式（当前章完成后停），不硬中断进行中的 LLM 调用。
- 全量构建 = 每章一次 Memory Extraction LLM 调用（失败章重试 ≤2 次）；成本≈章节数 × 单章提取。
- retry-failed 重跑失败章时，若 deepseek 持续返回坏 JSON，可能仍失败（保留 PARTIAL_FAILED，不无限自动重试）。
- Embedding 失败不影响 Job（该章记忆已建、无向量，后续可单独补嵌入——本期无独立 UI）。

## 14. P5-B 计划
在完整记忆底座上优化 Retrieval Selection：体系/关系定向 Query 的早期证据检索（B1/C1/C2 的 A 类错误）→ Query-aware retrieval、更高 fusion top-K、可选 LLM Reranker、Narrative State/Compression 评估。
