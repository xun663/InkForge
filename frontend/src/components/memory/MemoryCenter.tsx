import { useMemo, useState } from 'react'
import type { MemoryOverview } from '../../types'
import Button from '../common/Button'
import EmptyState from '../common/EmptyState'
import MemoryCard from './MemoryCard'
import MemoryFilter, { type MemoryTab } from './MemoryFilter'
import MemoryBuildPanel from './MemoryBuildPanel'

interface MemoryCenterProps {
  memory: MemoryOverview | null
  extracting: boolean
  onBuildMemory: () => void
  novelId: string | null
}

/**
 * 记忆中心：AI 记住了什么 —— 统计 + 类型过滤 + 本地搜索。
 * 数据来自现有 GET /api/novels/{id}/memory（overview），搜索为前端本地过滤。
 */
export default function MemoryCenter({ memory, extracting, onBuildMemory, novelId }: MemoryCenterProps) {
  const [tab, setTab] = useState<MemoryTab>('all')
  const [query, setQuery] = useState('')

  const q = query.trim().toLowerCase()

  const characters = useMemo(() => {
    if (!memory) return []
    if (q === '') return memory.characters
    return memory.characters.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.aliases.some((a) => a.toLowerCase().includes(q)) ||
        c.currentFacts.some((f) => (f.attribute + f.value + (f.targetCharacter ?? '')).toLowerCase().includes(q)),
    )
  }, [memory, q])

  const events = useMemo(() => {
    if (!memory) return []
    const all = memory.recentEvents
    if (q === '') return all
    return all.filter((e) => (e.title + e.description + e.location).toLowerCase().includes(q))
  }, [memory, q])

  const threads = useMemo(() => {
    if (!memory) return []
    const all = memory.recentSummaries.flatMap((s) =>
      s.unresolvedThreads.map((t) => ({ ordinal: s.chapterOrdinal, text: t })),
    )
    if (q === '') return all
    return all.filter((t) => t.text.toLowerCase().includes(q))
  }, [memory, q])

  const summaries = useMemo(() => {
    if (!memory) return []
    const all = memory.recentSummaries
    if (q === '') return all
    return all.filter((s) => s.summary.toLowerCase().includes(q))
  }, [memory, q])

  if (!memory) {
    return (
      <div className="memory-center">
        <h2>记忆中心</h2>
        <MemoryBuildPanel novelId={novelId} />
        <EmptyState title="尚未建立故事记忆" message="点击上方「全书记忆构建」可一次建立整本小说的记忆，或导入小说后使用工作台的快捷入口" />
      </div>
    )
  }

  const stats = memory.aggregateStats
  const showCharacters = tab === 'all' || tab === 'characters'
  const showEvents = tab === 'all' || tab === 'events'
  const showThreads = tab === 'all' || tab === 'threads'
  const showSummaries = tab === 'all' || tab === 'summaries'

  return (
    <div className="memory-center">
      <header className="memory-center-head">
        <h2>记忆中心</h2>
        <div className="memory-stats">
          <span>{stats.chaptersExtracted} 章记忆</span>
          <span>{stats.characters} 人物</span>
          <span>{stats.facts} 事实</span>
          <span>{stats.events} 事件</span>
          <span>{(stats.totalDurationMs / 1000).toFixed(1)}s</span>
        </div>
        <Button size="sm" onClick={onBuildMemory} disabled={extracting}>
          {extracting ? '提取中……' : '更新记忆'}
        </Button>
      </header>

      <MemoryBuildPanel novelId={novelId} />

      <MemoryFilter tab={tab} query={query} onTabChange={setTab} onQueryChange={setQuery} />

      {showCharacters && characters.length > 0 && (
        <section className="memory-section">
          <h3>人物</h3>
          {characters.slice(0, 40).map((c) => (
            <MemoryCard key={c.name} character={c} />
          ))}
          {characters.length > 40 && (
            <p className="meta">仅显示前 40 位，共 {characters.length} 人。用上方搜索缩小范围。</p>
          )}
        </section>
      )}

      {showEvents && events.length > 0 && (
        <section className="memory-section">
          <h3>最近事件</h3>
          <ul className="facts">
            {events.map((e, i) => (
              <li key={i}>
                第{e.chapterOrdinal + 1}章 {e.title}：{e.description}
              </li>
            ))}
          </ul>
        </section>
      )}

      {showThreads && threads.length > 0 && (
        <section className="memory-section">
          <h3>未解决线索</h3>
          <ul className="facts">
            {threads.map((t, i) => (
              <li key={i}>
                第{t.ordinal + 1}章：{t.text}
              </li>
            ))}
          </ul>
        </section>
      )}

      {showSummaries && summaries.length > 0 && (
        <section className="memory-section">
          <h3>章节摘要</h3>
          <ul className="facts">
            {summaries.map((s, i) => (
              <li key={i}>
                第{s.chapterOrdinal + 1}章：{s.summary}
              </li>
            ))}
          </ul>
        </section>
      )}

      {characters.length === 0 && events.length === 0 && threads.length === 0 && summaries.length === 0 && (
        <EmptyState title="没有匹配的记忆" message={query ? `没有包含「${query}」的记忆` : '暂无记忆'} />
      )}
    </div>
  )
}
