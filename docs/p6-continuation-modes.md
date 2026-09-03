# P6：续写模式（Continuation Modes）

日期：2026-09-03　状态：已实现（默认 profile 全量测试通过；postgres 路径 IT 需 Docker）

## 1. 目标

将单一"继续续写"升级为三种叙事策略模式，建立 **剧情规划 ↔ 正文生成** 的边界：

- `PLOT_CHOICE` 剧情选择：分析当前故事 → 给出 3~5 个候选方向 → 用户选择 → 确认计划 → 正式续写
- `ENDING` 完结：分析未解决剧情线/人物弧/伏笔/可舍弃支线 → 生成分阶段收束方案（Final Story Plan）→ 用户确认/修改/重新生成 → 按阶段逐步生成
- `EXPANSION` 拓展：挖掘未开发世界空间/势力/人物 → 新方向卡片 → 用户选择 → 确认计划 → 正式续写

核心产品定位：**AI 负责理解故事和执行剧情，用户负责决定故事往哪里走。**

## 2. 架构边界（硬约束）

| 规则 | 实现 | 回归测试 |
|---|---|---|
| Generation 绝不写 Story Memory | ContinuationService 零 memory 写依赖（不变式沿用 P5） | `PlanningMemoryIsolationTest`：种子「方源/境界=元婴/CURRENT」→ LLM 正文断言"方源突破化神。"→ 跑 legacy/规划/按计划生成 → Memory 纹丝不动 |
| 规划（Planning）唯一可写 PlotThread（规划层数据，非 Canon） | PlanningService 只持有 PlotThreadRepository 的写权限 | 同上 + `PlanningServiceTest.storyMemoryIsNeverTouchedByPlanningFlows` |
| 只有正式 Chapter 才可能进入未来 Extraction | `POST /novels/{id}/chapters` 只追加 Canon；提取仍仅由显式 `POST /memory/extract`（build）触发 | `ChapterAppendControllerTest.savingChapterDoesNotTriggerMemoryExtraction` |
| LLM 规划输出是候选，不是既定事实 | PlanOutputParser 两级校验；计划状态机 DRAFT→CONFIRMED→IN_PROGRESS→COMPLETED/ABANDONED，确认前不能生成 | `PlanningControllerTest` + `ContinuationPlanInjectionTest.draftPlanIsRejected` |

## 3. 新领域模型（com.inkforge.planning）

- `ContinuationMode`：PLOT_CHOICE / ENDING / EXPANSION（含中文 label 与 Prompt 标记）
- `StoryPlan`：planId/novelId/mode/title/summary/goal/expectedArc/steps/related*/userInstruction/analysis/status；`PlanStatus` 五态
- `PlanStep`（阶段）、`PlanDirection`（候选方向，临时数据不持久化）
- `EndingAnalysis`：mainArc/characterArcs/foreshadowing/worldState/droppableSubplots/finalConflict/endingDirection/threads
- `PlotThread`（+ `PlotThreadStatus`）：OPEN/RESOLVED/ABANDONED；`title_normalized` 归一化 upsert；
  写入方 = ENDING 分析（`PlotThreadMerger` 确定性合并，"LLM 建议、代码决定"）；
  **v1 无 RESOLVED/ABANDONED 写入方**（预留显式收束能力）
- `TaskType.PLANNING`：MockLlmProvider 按 prompt 中【规划模式：…】标记返回罐头 JSON

## 4. 生成链路的注入方式（零 SPI 变更）

```
planId → StoryPlanRepository 加载（校验归属/模式/状态）
       → PlanPromptRenderer 渲染附录（continuation.generation-with-plan.txt）
       → TokenCounter 计量，contextMaxTokens 扣减（附录取整 + 64 留白，超预算一半 → 400）
       → contextBuilder.buildWithTrace(novel, 预算, generationId)
       → 附录原地并入末条 user 消息（MemoryAware / RecentChapters 两 builder 通吃）
       → SSE 流式生成（GenerationLog 带 mode + planId）
```

legacy（mode/planId 均空）行为与 P5 字节级一致（`ContinuationPlanInjectionTest.legacyPathIsByteIdenticalEvenWhenConfirmedPlanExists` 钉死）。

## 5. 规划期检索 ≠ 生成期检索

`PlanningContextAssembler`（断点摘要 + 未解决线索 + 当前人物状态 + 最近事件 + OPEN PlotThread）
经 `QueryConstructionService.construct`（P5-B1 成果首次接入生产）→ `HybridRetrievalService.retrieveMulti`。
规划重线索/人物/世界观；生成期检索保持 P5 定稿不变。规划期检索失败降级为空、不写 RetrievalTrace（v1 已知限制）。

## 6. API

| 方法/路径 | 行为 |
|---|---|
| `POST /api/novels/{id}/continuations/options` | body `{mode, userInstruction?}`；PLOT_CHOICE/EXPANSION → `List<PlanDirection>`（不持久化）；ENDING → StoryPlan(DRAFT) + upsert PlotThread |
| `POST /api/novels/{id}/continuations/plan` | body `{mode, direction, userInstruction?}` → StoryPlan(DRAFT) |
| `GET /api/novels/{id}/plans`、`GET …/plans/{planId}` | 查询 |
| `POST …/plans/{planId}/confirm|complete|abandon` | 状态推进（非法转换 → 400） |
| `POST /api/novels/{id}/continuations`（既有 SSE） | DTO 扩展 `{mode, planId, stepIndex, userInstruction}`；均空 = legacy |
| `POST /api/novels/{id}/chapters`（新增） | 保存续写草稿为正式章节（201，只入 Canon，不触发提取） |

约定：单小说同时只允许一个活跃计划（DRAFT/CONFIRMED/IN_PROGRESS；partial unique index `uq_story_plan_active_novel` 兜底，V4 先例）；重新生成自动替换 DRAFT；CONFIRMED/IN_PROGRESS 挡新建。

## 7. 持久化（V5__p6_planning.sql）

`story_plan`、`plot_thread` 两表 + `generation_log` 增 `mode`/`plan_id` 两列（可空，向后兼容）。
双实现齐备：InMemory（default profile，域包内）+ JPA（`@Profile("postgres")`）。

## 8. 前端

- `ContinuationModeDrawer`：开始续写 → 三模式卡片 + 额外要求 + "直接续写（不使用规划）"
- `DirectionCards`：方向卡片（标题/摘要/理由/人物/冲突/目标），选择/刷新/自定义
- `EndingPlanPanel`：完结分析 + 收束阶段（选择起始阶段）+ 确认/重新生成
- `SaveChapterButton`（GenerationStatus 内，生成完成后出现）：保存为正式章节（提示"记忆提取仍需显式构建"）
- `App.tsx`：`runGeneration(body)` 统一生成入口；保存成功后刷新章节/断点

## 9. 测试（新增 8 类，全绿）

`PlanOutputParserTest`(7)、`PlotThreadMergerTest`(6)、`PlanningServiceTest`(11)、`PlanningControllerTest`(9)、
`PlanningMemoryIsolationTest`(3，核心回归)、`ContinuationPlanInjectionTest`(9)、`ChapterAppendControllerTest`(6)、
`PostgresPlanningIT`(4，无 Docker 自动 skip) + `PersistenceMappersTest` 增补(3)。

## 10. 已知限制 / Future Work

- ENDING 分阶段生成是手动逐步（stepIndex），无自动连写；自动推进依赖"保存章节后作为新断点"，留待后续
- 规划期检索不写 RetrievalTrace（前端 Trace 面板看不到规划检索）
- PlotThread 无 RESOLVED/ABANDONED 的生产写入方（预留）
- 保存章节在 postgres profile 下整本重写（O(chapters)，低频操作可接受）
- 计划附录不参与 MemoryAwareContextBuilder 的精确预算分配（预留扣减近似保证总额）
- StoryArc 未建表；主线/弧线只存在于 EndingAnalysis 规划数据中
- plans 列表页未做前端 UI（API 已就绪），中断的 DRAFT 计划可在服务端被下次规划替换
