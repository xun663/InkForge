import type {
  Breakpoint,
  Chapter,
  ChapterCreated,
  ContinuationMode,
  DoneMeta,
  LastChapter,
  LlmConfigDto,
  LlmConfigUpdate,
  MemoryBuildJob,
  MemoryExtractionRecord,
  MemoryOverview,
  NovelSummary,
  PlanDirection,
  RetrievalTrace,
  StoryPlan,
} from './types'

const BASE = '/api'

async function toJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `HTTP ${res.status}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      /* keep default message */
    }
    throw new Error(message)
  }
  return res.json() as Promise<T>
}

export async function listNovels(): Promise<NovelSummary[]> {
  return toJson(await fetch(`${BASE}/novels`))
}

export async function uploadNovel(file: File): Promise<NovelSummary> {
  const form = new FormData()
  form.append('file', file)
  return toJson(await fetch(`${BASE}/novels`, { method: 'POST', body: form }))
}

export async function fetchChapters(novelId: string): Promise<Chapter[]> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/chapters`))
}

export async function fetchLastChapter(novelId: string): Promise<LastChapter> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/chapters/last`))
}

export async function fetchChapter(novelId: string, ordinal: number): Promise<LastChapter> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/chapters/${ordinal}`))
}

export async function fetchBreakpoint(novelId: string): Promise<Breakpoint> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/breakpoint`))
}

/** Explicitly builds Story Memory for the most recent unprocessed chapters (synchronous). */
export async function extractMemory(
  novelId: string,
  count?: number,
): Promise<MemoryExtractionRecord[]> {
  return toJson(
    await fetch(`${BASE}/novels/${novelId}/memory/extract`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(count ? { count } : {}),
    }),
  )
}

export async function fetchMemory(novelId: string): Promise<MemoryOverview> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/memory`))
}

/** P3-F: fetch one retrieval trace for explainability (404 → null). */
export async function getRetrievalTrace(
  novelId: string,
  traceId: string,
): Promise<RetrievalTrace | null> {
  const res = await fetch(`${BASE}/novels/${novelId}/retrieval-traces/${traceId}`)
  if (res.status === 404) return null
  return toJson(await res)
}

/** P4-UI-E 后续：读取当前生效的 LLM 配置（安全视图，无 key 明文）。 */
export async function getLlmConfig(): Promise<LlmConfigDto> {
  return toJson(await fetch(`${BASE}/config/llm`))
}

/** P4-UI-E 后续：运行时更新 LLM 配置（Key 仅存内存，重启失效）。 */
export async function updateLlmConfig(dto: LlmConfigUpdate): Promise<LlmConfigDto> {
  return toJson(
    await fetch(`${BASE}/config/llm`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dto),
    }),
  )
}

// --- P5-A: Full Memory Build ---

export async function startMemoryBuild(novelId: string): Promise<MemoryBuildJob> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/memory/build`, { method: 'POST' }))
}

export async function getMemoryBuild(novelId: string): Promise<MemoryBuildJob | null> {
  const res = await fetch(`${BASE}/novels/${novelId}/memory/build`)
  if (res.status === 404 || res.status === 204) return null
  const text = await res.text()
  if (!text || text === 'null') return null
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }
  return JSON.parse(text) as MemoryBuildJob
}

async function buildAction(novelId: string, jobId: string, action: string): Promise<MemoryBuildJob> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/memory/build/${jobId}/${action}`, { method: 'POST' }))
}

export const pauseMemoryBuild = (novelId: string, jobId: string) => buildAction(novelId, jobId, 'pause')
export const resumeMemoryBuild = (novelId: string, jobId: string) => buildAction(novelId, jobId, 'resume')
export const cancelMemoryBuild = (novelId: string, jobId: string) => buildAction(novelId, jobId, 'cancel')
export const retryFailedMemoryBuild = (novelId: string, jobId: string) => buildAction(novelId, jobId, 'retry-failed')

// --- P6: Continuation Modes（剧情规划） ---

/**
 * 生成候选剧情方向。mode=PLOT_CHOICE/EXPANSION 返回方向数组（临时数据）；
 * mode=ENDING 返回 DRAFT 的 StoryPlan（同时 upsert 剧情线索）。
 */
export async function fetchContinuationOptions(
  novelId: string,
  body: { mode: ContinuationMode; userInstruction?: string },
): Promise<PlanDirection[] | StoryPlan> {
  return toJson(
    await fetch(`${BASE}/novels/${novelId}/continuations/options`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  )
}

/** 用户选定方向 → StoryPlan(DRAFT)。 */
export async function createPlanFromDirection(
  novelId: string,
  body: { mode: ContinuationMode; direction: PlanDirection; userInstruction?: string },
): Promise<StoryPlan> {
  return toJson(
    await fetch(`${BASE}/novels/${novelId}/continuations/plan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  )
}

export async function fetchPlans(novelId: string): Promise<StoryPlan[]> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/plans`))
}

export async function getPlan(novelId: string, planId: string): Promise<StoryPlan> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/plans/${planId}`))
}

async function planActionRequest(
  novelId: string,
  planId: string,
  action: string,
): Promise<StoryPlan> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/plans/${planId}/${action}`, { method: 'POST' }))
}

export const confirmPlan = (novelId: string, planId: string) => planActionRequest(novelId, planId, 'confirm')
export const completePlan = (novelId: string, planId: string) => planActionRequest(novelId, planId, 'complete')
export const abandonPlan = (novelId: string, planId: string) => planActionRequest(novelId, planId, 'abandon')

/** P6：保存续写草稿为正式章节（只入 Canon，不触发记忆提取）。 */
export async function saveChapter(
  novelId: string,
  body: { title?: string; content: string },
): Promise<ChapterCreated> {
  return toJson(
    await fetch(`${BASE}/novels/${novelId}/chapters`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  )
}

/** 按章节 ordinal 导出 TXT 并触发浏览器下载。 */
export async function exportChapters(novelId: string, ordinals: number[]): Promise<void> {
  const res = await fetch(`${BASE}/novels/${novelId}/chapters/export`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ordinals }),
  })
  if (!res.ok) {
    let message = `HTTP ${res.status}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      /* keep default */
    }
    throw new Error(message)
  }
  const blob = await res.blob()
  const star = /filename\*=UTF-8''([^;]+)/i.exec(res.headers.get('Content-Disposition') ?? '')
  const filename = star ? decodeURIComponent(star[1]) : 'chapters.txt'
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/** P3-F: recent traces of a novel (API ready; list UI is future work). */
export async function getRetrievalTraces(novelId: string, limit = 10): Promise<RetrievalTrace[]> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/retrieval-traces?limit=${limit}`))
}

export interface SseHandlers {
  onToken: (delta: string) => void
  onDone: (meta: DoneMeta) => void
  onError: (message: string) => void
}

/** 续写请求体：空对象 = 旧版直接续写；带 mode/planId = P6 按计划生成。 */
export interface ContinuationRequestBody {
  maxOutputTokens?: number
  temperature?: number
  mode?: ContinuationMode
  planId?: string
  stepIndex?: number
  userInstruction?: string
}

/**
 * Streams a continuation over SSE using native fetch + ReadableStream —
 * no third-party SSE library. Protocol: event: token / done / error.
 */
export async function streamContinuation(
  novelId: string,
  body: ContinuationRequestBody,
  handlers: SseHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${BASE}/novels/${novelId}/continuations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok || !res.body) {
    throw new Error(await errorMessage(res))
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, idx), handlers)
      buffer = buffer.slice(idx + 2)
    }
  }
}

function dispatch(rawEvent: string, handlers: SseHandlers): void {
  let eventName = 'message'
  let data = ''
  for (const line of rawEvent.split('\n')) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    else if (line.startsWith('data:')) data = line.slice(5).trimStart()
  }
  if (!data) return
  if (eventName === 'token') {
    handlers.onToken(JSON.parse(data) as string)
  } else if (eventName === 'done') {
    handlers.onDone(JSON.parse(data) as DoneMeta)
  } else if (eventName === 'error') {
    const parsed = JSON.parse(data) as string | { message?: string }
    handlers.onError(typeof parsed === 'string' ? parsed : (parsed.message ?? '未知错误'))
  }
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json()
    return body?.message ?? `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}
