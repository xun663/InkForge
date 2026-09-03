# InkForge Phase 2 Design Review —— Story Memory v1（最终版 v2）

> 状态：**设计定稿，待确认后实现**。v1 评审稿经修改意见修订，v1 → v2 变更见 §0。

---

## 0. v1 → v2 修改摘要

| # | 修改点 | 结论 |
|---|---|---|
| D1 | CharacterFact 三态原子事实模型 | 保留 |
| D2 | 事实键调整：关系事实增加 targetCharacter | **采纳**：普通事实键 `(characterId, category, canonicalAttribute)`；关系事实键 `(characterId, RELATIONSHIP, canonicalAttribute, targetCharacter)`，域模型预留 subject/target 语义 |
| D3 | 单次 LLM 结构化提取 | 保留 |
| D4 | 提取触发产品语义 | **调整**："上传小说"与"建立故事记忆"是两个动作；保留 `POST /api/novels/{id}/memory/extract`，默认流程为用户点击"建立故事记忆"或首次续写时提示；无后台任务/队列 |
| D5 | P2 不做 Vector/BM25/Reranker | 保留 |
| D6 | Context Budget 分区上限 | **调整**：45%/12%… 不做永久固定；抽象为 `ContextSection{priority, maxTokens, minTokens, required}`，Required 预留 → 优先级 → maxTokens → 动态分配余量；P3 的 RetrievedMemory 只需新增一个 section |
| — | UNCERTAIN 生命周期 | **调整**：UNCERTAIN 独立保留，普通 CURRENT **不**自动证伪 UNCERTAIN；CONFIRMED/REFUTED 证据推理留给 P5 |
| — | Extraction 输入策略 | **调整**：TokenCounter 驱动的自适应策略。普通几千字网文章节（≤ extraction-input-budget）**全文一次提取**（主路径）；超预算才进入 Chunk → 多次提取 → 确定性合并 fallback |
| — | Source Quote | 保留 + **增加 300 字符上限**（防 LLM 整段搬运原文）；必须为章节原文连续子串，否则拒收该条/触发重试 |
| — | MemoryExtractionStats | **新增**：characters/facts/events 计数、quotesValidated/Rejected、retries、duration、tokenUsage(可空)，用于测试/日志/前端面板/后续评估 |

---

## 1. P1 架构基线（不可破坏）

- 领域分包 `novel / chapter / context / generation / provider / common`，端口-适配器（Repository 接口 + InMemory 实现）
- `Novel(id, title, sourceFileName, List<Chapter>)`、`Chapter(ordinal, chapterNo, title, content)` 不可变 record；章节身份键 = `(novelId, ordinal)`
- `ContinuationContextBuilder` 接口 + `RecentChaptersContextBuilder`（预算参数、确定性截断）
- `ContinuationService` 编排：断点 → 预算构建 → `LlmProvider.stream` → SSE → GenerationLog
- `LlmProvider`（Mock 零 Key / OpenAI 兼容）、`PromptCatalog`、`TokenCounter`(JTokkit)
- **63 tests 全绿，P2 一切改动不得使其回归；ContinuationService 业务逻辑零改动（仅装配层换注入实现）**

## 2. Phase 2 总体架构

```text
章节（用户显式触发，后端同步处理，extract-window=3）
   ↓
MemoryExtractor（LLM 单次结构化提取：Summary + Characters + Facts + Events）
   ↓
ExtractionValidator（结构/枚举/范围/引用子串校验 + 有限重试）
   ↓
MemoryUpdateService（纯 Java 确定性合并：CREATE/UPDATE/SUPERSEDE/IGNORE/UNCERTAIN）
   ↓
StoryMemoryRepository（InMemory，端口隔离）
   ↓
MemoryAwareContextBuilder（ContextSection 优先级 + 动态预算分配）
   ↓
ContinuationService（P1 零业务改动）
   ↓
LLM Generation
```

**核心原则**：LLM 只负责**提取事实**；Java 负责**校验 / 合并 / 更新 / 生命周期**；Context Builder 负责**选择 / 优先级 / 预算**；P3 负责**检索**；P5 负责**验证/一致性**。

## 3. Domain Model

4 个记忆实体 + 1 个聚合仓储。不引入 Entity/World 抽象（P6 内容）：

| 实体 | 职责 |
|---|---|
| `ChapterSummary` | 章节结构化记忆（含未解决线索 + 提取统计） |
| `Character` | 人物本体（名字/别名/出场范围/存续状态） |
| `CharacterFact` | **原子状态事实**（CURRENT / SUPERSEDED / UNCERTAIN 三态生命周期） |
| `StoryEvent` | 剧情事件（无事件链，Event Graph 留 P6） |

"当前状态"是查询视图（`status=CURRENT` 的事实集合），不是单独维护的数据——避免与历史冲突。

## 4. ChapterSummary

```java
record ChapterSummary(
    String novelId, int chapterOrdinal,     // 身份键，与 Chapter 对齐
    String summary,
    List<String> keyEvents,
    List<SummaryCharacter> characters,      // {name, role(主角/配角/反派/其他)}
    List<String> locations,
    List<String> importantItems,
    List<String> unresolvedThreads,         // 续写的直接输入
    String model,
    String status,                          // SUCCESS / FAILED
    String errorMessage,
    MemoryExtractionStats stats,            // 见 §20，可空（FAILED 时）
    Instant createdAt)
```

## 5. Character

```java
record Character(
    String id, String novelId,
    String name,                    // 主名（trim + 全角空格归一）
    List<String> aliases,
    int firstChapter, int lastChapter,
    CharacterStatus status,         // ACTIVE / DEPARTED / DEAD / UNKNOWN
    Instant createdAt, Instant updatedAt)
```

## 6. CharacterFact（D1 + D2）

```java
record CharacterFact(
    String id,
    String characterId,             // subject（域模型预留 subject/target 语义）
    FactCategory category,          // IDENTITY/ABILITY/AFFILIATION/POSSESSION/STATE/
                                    // RELATIONSHIP/APPEARANCE/PERSONALITY/OTHER
    String attribute,               // 规范化属性名：境界/所属势力/当前状态/武器/身份/性格/关系…
    String value,                   // 关系事实中 = 关系类型（敌对/合作/利用/师徒…）
    String targetCharacter,         // 仅 RELATIONSHIP 事实非空：关系的对象角色名
    FactStatus status,              // CURRENT / SUPERSEDED / UNCERTAIN
    int validFromChapter,
    Integer validUntilChapter,      // null = 至今；被取代时写入
    double confidence,
    int sourceChapter,
    String sourceQuote,             // ≤300 字符，必须为原文连续子串
    Instant createdAt, Instant updatedAt)
```

**事实键（决定"什么算同一条事实"）**：
- 普通事实：`(characterId, category, canonicalAttribute)`
- 关系事实：`(characterId, RELATIONSHIP, canonicalAttribute, targetCharacter)`

例：`方源→敌对→白凝冰`、`方源→合作→古月漠尘` 是两条独立事实，**可以同时存在、互不覆盖**；同一目标的关系变化（敌对→合作）才触发取代。targetCharacter 以名字存储，读取/构建时 best-effort 解析为角色 id，未知名字保留文本。

**查询语义（确定性）**：当前状态 = `CURRENT`；历史 = `SUPERSEDED`（按 validFrom 排序即时间线）；传闻 = `UNCERTAIN`。

## 7. StoryEvent

```java
record StoryEvent(
    String id, String novelId, int chapterOrdinal,
    String title, String description,
    List<String> participants,     // 角色名，读取时 best-effort 解析
    String location,
    List<String> consequences,
    int importance,                // 1-5
    String sourceQuote,
    Instant createdAt)
```

**无 previousEvent/nextEvent** —— 事件链是 P6 Event Graph（需要 DAG），P2 不做。

## 8. Memory 生命周期分类

| 类型 | 实体 | 生命周期 | 覆盖策略 |
|---|---|---|---|
| Permanent Fact | `CharacterFact(IDENTITY)` | 长期有效，仅明确取代时置 SUPERSEDED | 仅追加 |
| Current State | `CharacterFact(CURRENT)` | 至 validUntilChapter 或"至今" | 取代 + 追加历史 |
| Historical State | `CharacterFact(SUPERSEDED)` | 永不删除 | 仅追加 |
| Uncertain Fact | `CharacterFact(UNCERTAIN)` | **独立保留，不被 CURRENT 自动证伪** | 仅追加 |
| Event / Summary | `StoryEvent / ChapterSummary` | 仅追加 | 仅追加 |

P2 无删除操作。CONFIRMED/REFUTED 证据推理留 P5 Consistency / Evidence Resolution。

## 9. Memory Update 规则（MemoryUpdateService，纯 Java）

```
逐角色：name 或 aliases 精确匹配 → 已有角色 MERGE / 新角色 CREATE
逐事实（键见 §6）：
  ├─ 无 CURRENT 同键             → CREATE（conf ≥ confirm-confidence → CURRENT；否则 UNCERTAIN）
  ├─ 同键同值                    → IGNORE
  ├─ 同键不同值 + 确认(conf≥阈值) → SUPERSEDE 旧 CURRENT（validUntil=本章）+ CREATE 新 CURRENT
  └─ 同键不同值 + 低置信          → CREATE UNCERTAIN（独立保留，不动 CURRENT）
UNCERTAIN 与 CURRENT 互不自动影响（P2 无证据推理）
MERGE aliases（仅精确、无碰撞时；歧义 → 不合并 + 新建 + 告警，宁分勿错并）
更新 lastChapter / status
输出 MemoryExtractionStats
```

## 10. Conflict Resolution

| 情况 | 规则 | 结果 |
|---|---|---|
| 明确升级（五转→六转） | 同键异值 + 确认 | 旧 SUPERSEDED(validUntil)，新 CURRENT；时间线 100→三转 200→五转 300→六转 完整可查 |
| 矛盾（属于A→脱离A） | 同上（值是"无/已脱离"） | 当前=已脱离，历史保留"属于A" |
| 不确定（"传闻已突破六转"） | conf < 0.7 → UNCERTAIN | 独立保留；后续出现"五转实力"的 CURRENT **不**证伪它；P5 再处理 |
| 多名字 | aliases 合并（精确、无碰撞） | 歧义不合并，保守策略 |

`confirm-confidence`（默认 0.7）配置化。

## 11. Source Tracking

```
Memory → sourceChapter(ordinal) → Chapter.content → sourceQuote（连续子串）
```

- `sourceQuote` **必须是章节原文的连续子串**（Java 逐字校验），否则拒收该 Fact/Event 或触发提取重试
- `sourceQuote` 长度上限 **300 字符**（防 LLM 整段搬运原文）
- 追溯链供 P5 回答"为什么系统认为方源现在是六转"

## 12. LLM Structured Extraction

**单次调用**（D3 保留）：一次 LLM 请求产出 Summary + Characters + Facts + Events。理由：省调用/延迟/成本，输出自洽，规模可控。不拆 ChapterSummaryService / CharacterExtractionService / EventExtractionService 三个类。

流水线（`MemoryExtractor`，走 `LlmProvider.complete()` 非流式）：

```
prompt 渲染（章节号/标题/按 §19 决定的输入文本）
  ↓ LLM（taskType=MEMORY_EXTRACTION，temperature=0.2 独立配置）
  ↓ 严格 JSON 解析（fences 剥离 + 平衡括号提取）
  ↓ ExtractionValidator：结构(Jackson 绑定) → 枚举 → 取值范围 → sourceQuote 子串校验 + 300 上限
  ↓ 失败 → memory.repair.txt 追加解析错误反馈重试（max-retries=2，配置）
  ↓ 仍失败 → ChapterSummary(status=FAILED, errorMessage)，不阻断续写主链路
```

## 13. Prompt 设计（prompts/memory.extraction.txt，集中管理）

```
你是 InkForge 的故事记忆提取引擎。阅读下方章节全文，输出严格的 JSON（不要任何 JSON 之外的内容）。

章节：第{{chapterNo}}章 《{{chapterTitle}}》

提取要求：
1. summary：2-4 句客观概括；keyEvents 按发生顺序；unresolvedThreads 列出尚未解决的线索。
2. characters：只提取本章实际出场/被明确提及的角色，每人给出 facts：
   - category 只能取：IDENTITY/ABILITY/AFFILIATION/POSSESSION/STATE/RELATIONSHIP/APPEARANCE/PERSONALITY/OTHER
   - attribute 使用规范词表：身份/境界/所属势力/当前状态/武器/性格/外貌/关系/其他（"修为/实力"一律写"境界"，"宗门/势力"一律写"所属势力"）
   - value 简短明确（≤20字）；confidence 0-1（事实明确=0.9+，传闻/暗示=0.3-0.6）
   - 关系类事实必须给出 targetCharacter（关系对象的名字），value 写关系类型（敌对/合作/利用/师徒/…）
   - sourceQuote 必须逐字引用本章原文中支持该事实的句子（≤300字）
3. events：本章重要剧情事件，importance 1-5，同样给出 sourceQuote。
4. 未提及的字段输出空数组，不要编造。

{{content}}

输出 JSON 格式（严格遵循，不要省略字段）：
{{schema}}
```

`{{schema}}` 由 Java 从 DTO 生成（单一事实来源）；修复重试用 `memory.repair.txt`。

## 14. DTO / Schema（LLM 输出契约）

```java
record ChapterExtractionResult(
    SummaryDto summary,                 // {summary, keyEvents[], characters[{name,role}],
                                        //  locations[], importantItems[], unresolvedThreads[]}
    List<ExtractedCharacter> characters,    // {name, aliases[], facts[]}
    List<ExtractedEvent> events)            // {title, description, participants[],
                                            //  location, consequences[], importance, sourceQuote}

record ExtractedFact(FactCategory category, String attribute, String value,
                     String targetCharacter,   // 关系事实必填，其余为空
                     double confidence, String sourceQuote)
```

校验优先级：**结构 → 枚举 → 取值范围 → 引用子串 + 长度上限**。任何失败走重试，耗尽降级 FAILED。

## 15. Repository 接口（端口）

```java
interface StoryMemoryRepository {
    void saveSummary(ChapterSummary s);
    Optional<ChapterSummary> findSummary(String novelId, int ordinal);
    List<ChapterSummary> findSummaries(String novelId, int fromOrdinal, int toOrdinal);

    Character saveCharacter(Character c);
    Optional<Character> findCharacterByName(String novelId, String name); // name 或 aliases 精确匹配
    List<Character> findCharacters(String novelId);

    void saveFact(CharacterFact f);
    List<CharacterFact> findFacts(String characterId);          // 全部（含历史/传闻）
    List<CharacterFact> findCurrentFacts(String characterId);   // CURRENT

    void saveEvent(StoryEvent e);
    List<StoryEvent> findEvents(String novelId, int limit, boolean recentFirst);
}
```

`InMemoryStoryMemoryRepository`：ConcurrentHashMap + 索引。P3 换 JPA/PostgreSQL 上层零改动。

## 16. Service 边界（4 个）

| 类 | 职责 |
|---|---|
| `MemoryExtractor` | LLM 调用 + 解析 + 校验 + 重试 → `ChapterExtractionResult` |
| `MemoryUpdateService` | 纯 Java 合并规则（§9/§10）+ `MemoryExtractionStats` |
| `StoryMemoryService` | 门面：extract → update → persist；查询（当前状态/时间线/最近事件/未解决线索） |
| `MemoryAwareContextBuilder` | 实现 `ContinuationContextBuilder`，ContextSection 分配（§17/§18） |

**ContinuationService 零业务逻辑改动**（装配层换实现类）。

## 17. Context Builder 接入 Memory（D6）

**ContextSection 抽象**（上限不是永久固定规则，是配置数据）：

```java
record ContextSection(String key, int priority, int maxTokens, int minTokens, boolean required)
```

```yaml
inkforge:
  context:
    context-max-tokens: 8192
    breakpoint-tail-chars: 2000
    sections:
      breakpoint-text:    { priority: 1, required: true,  min-tokens: 2048, max-tokens: 4096 }
      breakpoint-memory:  { priority: 2, required: true,  min-tokens: 128,  max-tokens: 1024 }  # 断点摘要+unresolvedThreads
      current-facts:      { priority: 3, required: false, min-tokens: 0,    max-tokens: 1024 }  # 当前人物 CURRENT facts
      recent-events:      { priority: 4, required: false, min-tokens: 0,    max-tokens: 768 }
      recent-chapters:    { priority: 5, required: false, min-tokens: 0,    max-tokens: 1280 }  # P1 滚动窗口延续
      fact-history:       { priority: 6, required: false, min-tokens: 0,    max-tokens: 512 }   # 当前人物 SUPERSEDED 时间线
      older-summaries:    { priority: 7, required: false, min-tokens: 0,    max-tokens: 256 }
```

**记忆选择（P2 确定性规则，无检索）**：
- "当前人物" = 断点章节摘要出场角色 ∪ 最近 K 章摘要角色
- 当前人物 → CURRENT facts 全量 + SUPERSEDED 时间线
- 最近事件 = 最近 N 条（chapterOrdinal 倒序）
- 断点章节摘要 + unresolvedThreads 必带
- 该小说无任何 memory → 自动降级 P1 `RecentChaptersContextBuilder` 行为（绝不因记忆缺失失败）

**P3 扩展**：新增 `retrieved-memory` section 配置即可，Builder 零重构。

## 18. Token Budget 分配算法（两阶段，确定性）

```
1. 预留固定成本：system prompt + user 模板骨架
2. Required 阶段：按 priority 顺序为 required sections 预留 minTokens（不足 → 配置错误，同 P1"预算过小"语义）
3. 贪心阶段：按 priority 顺序，每段从剩余预算中分配 min(剩余, maxTokens)，段内容用 fitTail 确定性截断（段头 token 计入）
4. 不变式：totalTokens ≤ context-max-tokens（测试断言）
```

## 19. Adaptive Extraction 输入策略（v2 核心调整）

**判断依据 = TokenCounter（复用 P1），不是字符数。**

```
tokenCount(chapter.content) ≤ extraction-input-budget（默认 12000 tokens）
   → 情况 A：全文一次提取（正常路径，P2 主路径）
tokenCount > budget
   → 情况 B/C：超长 fallback
       Chunk（≤budget 的 token 窗口，可配 overlap）
       → 每 chunk 独立 Structured Extraction
       → MemoryUpdateService 确定性合并（事实键天然去重）
```

- 普通网络小说章节（几千字）**全文提取、零截断**——正常路径简单可靠
- 超长章节（2万+ 字）自动走 fallback，不提前引入复杂架构
- 配置：`extraction-input-budget`（tokens）、`chunk-overlap-chars`（默认 200）

## 20. MemoryExtractionStats（新增，可观测）

```java
record MemoryExtractionStats(
    int charactersExtracted, int factsExtracted, int eventsExtracted,
    int quotesValidated, int quotesRejected,
    int retries, long durationMs,
    LlmUsage tokenUsage)   // Provider 无法提供时为空
```

嵌入 `ChapterSummary.stats`；extract 端点返回；用于：单元测试断言、Debug、GenerationLog(type=EXTRACTION)、前端 Memory 面板、后续 Memory Quality Evaluation（Quote Validation Rate / Conflict Rate 等评估体系的基础）。

## 21. 提取触发产品流程（D4 调整）

- **"上传小说" ≠ "建立故事记忆"**，两个独立动作
- `POST /api/novels/{id}/memory/extract` 同步处理最近 `extract-window=3` 章，返回逐章结果 + Stats
- 默认产品流程：上传 → 解析 → Novel Ready → 用户点击 **"建立故事记忆"**；或用户直接点续写 → 前端发现无记忆 → 提示"是否建立故事记忆？"
- 无后台任务 / 消息队列 / 异步 Worker；无论有无记忆，续写均可用（无记忆时降级 P1 上下文）
- 前端右侧 Memory 面板轮询 `GET /api/novels/{id}/memory`（人物当前状态+历史折叠 / 最近事件 / 未解决线索 / 提取统计）

## 22. 测试方案（新测试类预估 12 个，全部零 Key）

| 类 | 覆盖 |
|---|---|
| `MemoryUpdateServiceTest`（核心，纯单测） | 新角色 / 已有合并 / 别名 / 状态更新→取代+历史 / 同值 IGNORE / 低置信→UNCERTAIN / **关系事实同属性不同目标互不覆盖** / **同键关系更新才取代** / **UNCERTAIN 不被 CURRENT 证伪** / 歧义不合并 / Stats |
| `MemoryExtractorTest`（Mock LLM） | 正常 JSON / 非法 JSON→重试→成功 / 重试耗尽→FAILED 降级 / quote 非子串被拒 / quote 超 300 被拒 / 置信度越界 / fences 剥离 |
| `AdaptiveExtractionTest` | 章节 ≤ budget 全文一次调用 / > budget 走 chunk+merge（Mock 计数断言调用次数） |
| `InMemoryStoryMemoryRepositoryTest` | CRUD + 名字/别名索引 + 时间线排序 |
| `StoryMemoryServiceTest` | 全流水线（Mock canned JSON）→ 落库 + Stats |
| `MemoryAwareContextBuilderTest` | 总 token ≤ 预算（全场景）/ required 下限预留 / 贪心分配顺序 / 无记忆降级 P1 / 当前事实必带 |
| `MemoryControllerTest`（MockMvc） | memory 查询 / extract 端点 / SSE 续写共存 |
| 回归门禁 | **P1 63 tests 原样通过** |

Mock 方案：`LlmRequest.taskType`（CONTINUATION / MEMORY_EXTRACTION，默认前者）——MockLlmProvider 按 taskType 返回 canned 提取 JSON 或续写段落；OpenAI 兼容实现忽略。canned JSON 与 schema 同源 fixture 测试防漂移。

## 23. P2 Definition of Done

- [ ] 每章可以生成结构化 Summary
- [ ] 普通几千字章节默认使用全文提取（零截断）
- [ ] 超过 Extraction Budget 才进入超长文本 fallback（chunk+merge）
- [ ] 自动提取人物，支持新人物 / 已有人物
- [ ] 支持 Alias（精确、无碰撞合并）
- [ ] CharacterFact 支持 CURRENT / SUPERSEDED / UNCERTAIN
- [ ] 普通事实不会错误覆盖历史（时间线可查）
- [ ] Relationship Fact 支持 targetCharacter，多关系共存不互覆
- [ ] UNCERTAIN 不会被普通 CURRENT 自动证伪
- [ ] Memory 可追溯 sourceChapter → 原文 → sourceQuote
- [ ] sourceQuote 通过原文 substring 校验 + ≤300 字符
- [ ] LLM Structured Output 有完整校验 + 有限重试
- [ ] 重试失败不阻断 P1 续写主链路
- [ ] Context Builder 使用 Memory 且严格服从 Token Budget
- [ ] P2 不依赖 Vector/BM25/Reranker/Embedding
- [ ] Mock Provider 零 Key 可完成 P2 全流程
- [ ] MemoryExtractionStats 可观测（API + 前端面板）
- [ ] 前端可查看 Story Memory
- [ ] **P1 原有 63 tests 全部通过（零修改）**
- [ ] P2 新增测试全部通过

## 24. P1 代码修改点（最小侵入清单）

| 修改 | 性质 |
|---|---|
| `LlmRequest` + `taskType` 字段 | 增量，默认 CONTINUATION，P1 行为不变 |
| `MockLlmProvider` 按 taskType 分派 canned 响应 | 增量 |
| `GenerationLog` + `type` 字段（CONTINUATION/EXTRACTION） | 增量 |
| `application.yml` + `inkforge.memory.*`、`inkforge.context.sections` | 增量 |
| `prompts/` + `memory.extraction.txt` / `memory.repair.txt` | 新增 |
| Bean 装配：注入 `MemoryAwareContextBuilder`（内部降级 P1） | 装配层 |
| 前端：上传后可选"建立故事记忆" + 右侧 Memory 面板 | 新增 |

**不改动**：Chapter/Novel/解析器/切分器/SSE 协议/P1 两个 Controller/`RecentChaptersContextBuilder`（保留作 baseline 与降级路径，P7 Benchmark 对照组）/`ContinuationService` 业务逻辑。

## 25. 架构风险

| 风险 | 缓解 |
|---|---|
| LLM 结构化输出不可靠 | 严格解析 + 有限重试 + FAILED 降级（不阻断主线） |
| 属性名漂移 | 提示词规范词表 + Java canonicalAttribute 别名映射 |
| 别名过度合并 | 仅精确匹配合并；歧义不合并 + 告警 |
| 关系事实误覆盖 | 关系键含 targetCharacter（§6），专项测试 |
| 超长章节 | Adaptive Extraction fallback（§19），正常路径不受影响 |
| 记忆挤占原文预算 | ContextSection required 下限 + 贪心上限，测试断言总预算 |
| InMemory 膨胀 | P2 窗口提取数据量小；P3 落库解决 |
| P1 回归 | 全增量改动 + 63 测试门禁 |
| Mock 与 schema 漂移 | canned JSON 与 schema 同源 fixture 测试 |

## 26. P2 明确不做

Vector Database / BM25 / Reranker / Embedding / Knowledge Graph / Event Graph / Timeline Graph / 世界观实体系统 / 自动证据推理 / CONFIRMED / REFUTED 复杂状态 / 后台异步任务 / 消息队列 / 分布式任务 / Memory 压缩 —— 分别留给 P3（检索）、P5（一致性/证据）、P6（叙事图谱）。

## 27. P2 → P3 衔接

P2 产出**带章节锚点的结构化事实**（键明确、状态机清晰）。P3 的 `memory_chunk` 只是这些实体的反规范化视图（summary/fact/event → 嵌入文本 + embedding + BM25 字段）；混合检索查 chunk，实体/事件检索直接查 P2 的表；**当前人物状态永远直取、不参与检索**。Context Builder 通过新增 `retrieved-memory` ContextSection 接入，预算机制复用——这正是 P2 将 Memory Construction 与 Retrieval 解耦的红利。
