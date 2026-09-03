/** API DTOs mirroring the backend contracts. */

export interface NovelSummary {
  id: string
  title: string
  sourceFileName: string
  chapterCount: number
}

export interface Chapter {
  ordinal: number
  chapterNo: number | null
  title: string
  charCount: number
}

export interface LastChapter extends Chapter {
  content: string
}

export interface Breakpoint {
  chapterOrdinal: number
  chapterNo: number | null
  chapterTitle: string
  tailExcerpt: string
}

/** Metadata carried by the SSE done event; also persisted as the GenerationLog. */
export interface DoneMeta {
  generationId: string
  provider: string
  model: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  latencyMs: number
  estimatedCostUsd: number
  /** P3-E+：检索观测（旧 done 事件可能缺失，保持兼容） */
  retrievedCount?: number | null
  retrievalTraceId?: string | null
}

// --- Memory Build Job (P5-A 全量记忆构建) ---

export type MemoryBuildStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'PARTIAL_FAILED' | 'CANCELLED'

export interface MemoryBuildJob {
  jobId: string
  novelId: string
  status: MemoryBuildStatus
  totalChapters: number
  successChapters: number
  failedChapters: number
  currentOrdinal: number
  progress: number
  failedOrdinals: number[]
}

// --- Runtime LLM Config (P4-UI-E 后续) ---

/** 安全视图：绝不含 apiKey 明文，只有 apiKeyConfigured 布尔。 */
export interface LlmConfigDto {
  provider: string
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  supportedProviders: string[]
}

/** apiKey: null=保持不变、""=清除、非空=更新。 */
export interface LlmConfigUpdate {
  provider?: string | null
  baseUrl?: string | null
  model?: string | null
  apiKey?: string | null
}

// --- Retrieval Trace (P3-E/F) ---

export interface TraceRetrievalResult {
  chunkId: string
  novelId: string
  chapterOrdinal: number
  memoryType: 'SUMMARY' | 'FACT' | 'EVENT'
  sourceId: string
  text: string
  score: number
}

export interface RetrievalTrace {
  id: string
  novelId: string
  generationId: string
  queries: string[]
  pipeline: {
    bm25?: TraceRetrievalResult[]
    vector?: TraceRetrievalResult[]
    fusion?: TraceRetrievalResult[]
    rerank?: TraceRetrievalResult[]
    final?: TraceRetrievalResult[]
  }
  createdAt: string
}

// --- Story Memory (Phase 2) ---

export interface ExtractionStats {
  charactersExtracted: number
  factsExtracted: number
  eventsExtracted: number
  quotesValidated: number
  quotesRejected: number
  retries: number
  durationMs: number
  tokenUsage: { promptTokens: number; completionTokens: number } | null
}

export interface MemoryExtractionRecord {
  novelId: string
  chapterOrdinal: number
  status: string
  errorMessage: string | null
  model: string
  stats: ExtractionStats
  createdAt: string
}

export interface CharacterFactView {
  category: string
  attribute: string
  value: string
  targetCharacter: string | null
  status: string
  validFromChapter: number
  validUntilChapter: number | null
  confidence: number
  sourceChapter: number
  sourceQuote: string
}

export interface CharacterView {
  name: string
  aliases: string[]
  status: string
  currentFacts: CharacterFactView[]
  historyFacts: CharacterFactView[]
}

export interface StoryEventView {
  chapterOrdinal: number
  title: string
  description: string
  participants: string[]
  location: string
  consequences: string[]
  importance: number
}

export interface SummaryView {
  chapterOrdinal: number
  summary: string
  unresolvedThreads: string[]
}

export interface MemoryOverview {
  novelId: string
  lastExtractedOrdinal: number | null
  characters: CharacterView[]
  recentEvents: StoryEventView[]
  recentSummaries: SummaryView[]
  aggregateStats: {
    chaptersExtracted: number
    characters: number
    facts: number
    events: number
    totalDurationMs: number
  }
}

// --- Continuation Modes (P6 续写模式) ---

/** 三种叙事策略：剧情选择 / 完结 / 拓展（与后端 ContinuationMode 枚举一一对应）。 */
export type ContinuationMode = 'PLOT_CHOICE' | 'ENDING' | 'EXPANSION'

export type PlanStatus = 'DRAFT' | 'CONFIRMED' | 'IN_PROGRESS' | 'COMPLETED' | 'ABANDONED'

export type PlotThreadStatus = 'OPEN' | 'RESOLVED' | 'ABANDONED'

export interface PlanStep {
  index: number
  title: string
  summary: string
  phaseGoal: string
}

export interface EndingThread {
  title: string
  summary: string
  resolution: string
  firstSeenChapter: number | null
  relatedCharacters: string[]
}

export interface CharacterArc {
  name: string
  arc: string
}

/** ENDING 模式的完结分析（规划层数据，非 Canon）。 */
export interface EndingAnalysis {
  mainArc: string | null
  characterArcs: CharacterArc[]
  foreshadowing: string[]
  worldState: string | null
  droppableSubplots: string[]
  finalConflict: string | null
  endingDirection: string | null
  threads: EndingThread[]
}

/** 剧情计划：规划与正文生成的边界；确认后才用于生成，绝不自动写入 Story Memory。 */
export interface StoryPlan {
  planId: string
  novelId: string
  mode: ContinuationMode
  title: string | null
  summary: string | null
  goal: string | null
  expectedArc: string | null
  steps: PlanStep[]
  relatedCharacters: string[]
  relatedThreads: string[]
  relatedEvents: string[]
  userInstruction: string | null
  analysis: EndingAnalysis | null
  status: PlanStatus
  createdAt: string
  updatedAt: string
}

/** 候选剧情方向（临时数据，不持久化）。 */
export interface PlanDirection {
  title: string
  summary: string
  rationale: string | null
  involvedCharacters: string[]
  relatedThreads: string[]
  relatedWorldElements: string[]
  possibleConflict: string | null
  newConflict: string | null
  directionGoal: string | null
}

export interface PlotThread {
  id: string
  novelId: string
  title: string
  summary: string | null
  status: PlotThreadStatus
  firstSeenChapter: number | null
  lastSeenChapter: number | null
  relatedCharacters: string[]
  createdAt: string
  updatedAt: string
}

export interface ChapterCreated {
  ordinal: number
  chapterNo: number | null
  title: string
  charCount: number
}
