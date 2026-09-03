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
