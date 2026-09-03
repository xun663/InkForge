import type {
  Breakpoint,
  Chapter,
  DoneMeta,
  LastChapter,
  LlmConfigDto,
  LlmConfigUpdate,
  MemoryExtractionRecord,
  MemoryOverview,
  NovelSummary,
  RetrievalTrace,
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

/** P3-F: recent traces of a novel (API ready; list UI is future work). */
export async function getRetrievalTraces(novelId: string, limit = 10): Promise<RetrievalTrace[]> {
  return toJson(await fetch(`${BASE}/novels/${novelId}/retrieval-traces?limit=${limit}`))
}

export interface SseHandlers {
  onToken: (delta: string) => void
  onDone: (meta: DoneMeta) => void
  onError: (message: string) => void
}

/**
 * Streams a continuation over SSE using native fetch + ReadableStream —
 * no third-party SSE library. Protocol: event: token / done / error.
 */
export async function streamContinuation(
  novelId: string,
  handlers: SseHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${BASE}/novels/${novelId}/continuations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: '{}',
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
