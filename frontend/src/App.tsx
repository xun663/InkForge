import { useCallback, useEffect, useState } from 'react'
import {
  extractMemory,
  fetchBreakpoint,
  fetchChapters,
  fetchLastChapter,
  fetchMemory,
  streamContinuation,
} from './api'
import AppShell, { type AppView } from './components/layout/AppShell'
import EmptyState from './components/common/EmptyState'
import ErrorBoundary from './components/common/ErrorBoundary'
import ErrorState from './components/common/ErrorState'
import Button from './components/common/Button'
import Drawer from './components/common/Drawer'
import NovelList from './components/novel/NovelList'
import ChapterList from './components/novel/ChapterList'
import StoryViewer from './components/editor/StoryViewer'
import GenerationStatus, { type GenStage } from './components/editor/GenerationStatus'
import MemoryCard from './components/memory/MemoryCard'
import MemoryCenter from './components/memory/MemoryCenter'
import SettingsView from './components/settings/SettingsView'
import { useNovels } from './hooks/useNovels'
import { useMediaQuery } from './hooks/useMediaQuery'
import type {
  Breakpoint,
  Chapter,
  DoneMeta,
  LastChapter,
  MemoryExtractionRecord,
  MemoryOverview,
} from './types'
import './App.css'

/** 占位视图：导航目标尚未实现（不制作假功能）。 */
function ComingSoon({ title }: { title: string }) {
  return (
    <div className="coming-soon">
      <EmptyState title={title} message="即将支持" />
    </div>
  )
}

export default function App() {
  const [activeView, setActiveView] = useState<AppView>('workspace')
  const { novels, activeNovel, activeNovelId, loading, error, importNovel, selectNovel } = useNovels()

  const [stage, setStage] = useState<GenStage>('idle')
  const [chapters, setChapters] = useState<Chapter[]>([])
  const [breakpoint, setBreakpoint] = useState<Breakpoint | null>(null)
  const [lastChapter, setLastChapter] = useState<LastChapter | null>(null)
  const [selectedOrdinal, setSelectedOrdinal] = useState<number | null>(null)
  const [output, setOutput] = useState('')
  const [doneMeta, setDoneMeta] = useState<DoneMeta | null>(null)
  const [genError, setGenError] = useState('')
  const [loadError, setLoadError] = useState('')
  const [memoryError, setMemoryError] = useState('')
  const [memory, setMemory] = useState<MemoryOverview | null>(null)
  const [extracting, setExtracting] = useState(false)
  const [lastExtraction, setLastExtraction] = useState<MemoryExtractionRecord[]>([])
  const [showTrace, setShowTrace] = useState(false)
  const [memoryOpen, setMemoryOpen] = useState(false)

  // 窄屏（≤1100px）：记忆区从右侧栏变为可展开 Drawer
  const isNarrow = useMediaQuery('(max-width: 1100px)')

  /** 切换/导入小说 → 加载章节、断点、末章全文。 */
  useEffect(() => {
    if (!activeNovelId) {
      setChapters([])
      setBreakpoint(null)
      setLastChapter(null)
      setSelectedOrdinal(null)
      setOutput('')
      setDoneMeta(null)
      setMemory(null)
      setLastExtraction([])
      setShowTrace(false)
      setMemoryOpen(false)
      setLoadError('')
      return
    }
    let cancelled = false
    setStage('ready')
    setLoadError('')
    Promise.all([
      fetchChapters(activeNovelId),
      fetchBreakpoint(activeNovelId),
      fetchLastChapter(activeNovelId),
    ])
      .then(([chapterList, bp, last]) => {
        if (cancelled) return
        setChapters(chapterList)
        setBreakpoint(bp)
        setLastChapter(last)
        setSelectedOrdinal(bp.chapterOrdinal)
      })
      .catch((e) => {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelled = true
    }
  }, [activeNovelId])

  const handleImport = useCallback(
    async (file: File) => {
      try {
        await importNovel(file)
      } catch {
        // 导入错误由 NovelList 内的 useNovels.error 展示，避免双处重复报错
      }
    },
    [importNovel],
  )

  const handleSelectChapter = useCallback((ordinal: number) => {
    setSelectedOrdinal(ordinal)
  }, [])

  /** "上传小说" 与 "建立故事记忆" 是两个动作：显式触发，同步提取最近 N 章。 */
  async function handleBuildMemory() {
    if (!activeNovel || extracting) return
    setExtracting(true)
    setMemoryError('')
    try {
      const records = await extractMemory(activeNovel.id)
      setLastExtraction(records)
      const overview = await fetchMemory(activeNovel.id)
      setMemory(overview)
    } catch (e) {
      setMemoryError(e instanceof Error ? e.message : String(e))
    } finally {
      setExtracting(false)
    }
  }

  async function handleContinue() {
    if (!activeNovel) return
    setStage('generating')
    setOutput('')
    setDoneMeta(null)
    setGenError('')
    try {
      await streamContinuation(activeNovel.id, {
        onToken: (delta) => setOutput((prev) => prev + delta),
        onDone: (meta) => {
          setDoneMeta(meta)
          setStage('done')
        },
        onError: (message) => {
          setGenError(message)
          setStage('done')
        },
      })
    } catch (e) {
      setGenError(e instanceof Error ? e.message : String(e))
      setStage('done')
    }
  }

  const memoryBuilt = memory != null && memory.lastExtractedOrdinal != null
  const stats = lastExtraction[0]?.stats

  /** 记忆面板内容：宽屏内联右侧栏 / 窄屏 Drawer 共用。 */
  const renderMemoryPanel = () => (
    <>
      {memoryError && <ErrorState message={memoryError} />}
      {!activeNovel ? (
        <div className="memory-empty">
          <p>导入小说后可在此建立故事记忆。</p>
        </div>
      ) : !memoryBuilt ? (
        <div className="memory-empty">
          <p>尚未建立故事记忆。</p>
          <Button onClick={() => void handleBuildMemory()} disabled={extracting} size="sm">
            {extracting ? '提取中……' : '建立故事记忆'}
          </Button>
          {stats && (
            <p className="meta">
              ✓ 摘要 · {stats.charactersExtracted} 人物 · {stats.factsExtracted} 事实 ·{' '}
              {stats.eventsExtracted} 事件 · {stats.quotesValidated}/{stats.quotesValidated + stats.quotesRejected}{' '}
              引用通过
            </p>
          )}
        </div>
      ) : (
        <>
          <p className="meta">
            ✓ {memory.aggregateStats.chaptersExtracted} 章记忆 · {memory.aggregateStats.characters} 人物 ·{' '}
            {memory.aggregateStats.facts} 事实 · {memory.aggregateStats.events} 事件 ·{' '}
            {(memory.aggregateStats.totalDurationMs / 1000).toFixed(1)}s
          </p>
          {memory.characters.map((c) => (
            <MemoryCard key={c.name} character={c} />
          ))}
          {memory.recentEvents.length > 0 && (
            <div className="mem-section">
              <h3>最近事件</h3>
              <ul className="facts">
                {memory.recentEvents.map((e, i) => (
                  <li key={i}>
                    第{e.chapterOrdinal + 1}章 {e.title}：{e.description}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {memory.recentSummaries.length > 0 && (
            <div className="mem-section">
              <h3>未解决线索</h3>
              <ul className="facts">
                {memory.recentSummaries
                  .flatMap((s) => s.unresolvedThreads.map((t) => ({ ordinal: s.chapterOrdinal, text: t })))
                  .slice(0, 8)
                  .map((t, i) => (
                    <li key={i}>
                      第{t.ordinal + 1}章：{t.text}
                    </li>
                  ))}
              </ul>
            </div>
          )}
          <Button
            variant="secondary"
            className="rebuild"
            onClick={() => void handleBuildMemory()}
            disabled={extracting}
          >
            {extracting ? '提取中……' : '继续提取记忆'}
          </Button>
        </>
      )}
    </>
  )

  return (
    <AppShell activeView={activeView} onNavigate={setActiveView}>
      {activeView === 'workspace' ? (
        <div className="workspace">
          <aside className="side-rail">
            <NovelList
              novels={novels}
              activeNovelId={activeNovelId}
              loading={loading}
              error={error}
              disabled={stage === 'generating' || extracting}
              onSelect={selectNovel}
              onImport={(f) => void handleImport(f)}
            />
            <ChapterList
              chapters={chapters}
              activeOrdinal={selectedOrdinal}
              onSelect={handleSelectChapter}
            />
          </aside>

          <main className="main">
            {isNarrow && activeNovel && (
              <div className="main-toolbar">
                <Button variant="secondary" size="sm" onClick={() => setMemoryOpen(true)}>
                  📖 故事记忆
                </Button>
              </div>
            )}
            {loadError && <ErrorState message={loadError} />}
            <ErrorBoundary>
              {activeNovel && breakpoint && selectedOrdinal != null ? (
                <StoryViewer
                  novel={activeNovel}
                  chapters={chapters}
                  selectedOrdinal={selectedOrdinal}
                  lastChapter={lastChapter}
                  breakpoint={breakpoint}
                  generating={stage === 'generating'}
                  onContinue={() => void handleContinue()}
                />
              ) : (
                <section className="panel">
                  <EmptyState
                    title="导入一部小说开始创作"
                    message="支持 TXT（UTF-8 / GBK），上传后即可查看章节并让 AI 续写"
                  />
                </section>
              )}

              <GenerationStatus
                stage={stage}
                output={output}
                doneMeta={doneMeta}
                error={genError}
                novelId={activeNovelId}
                showTrace={showTrace}
                onToggleTrace={(value) => setShowTrace((prev) => value ?? !prev)}
              />
            </ErrorBoundary>
          </main>

          {isNarrow ? (
            <Drawer open={memoryOpen} title="故事记忆" onClose={() => setMemoryOpen(false)}>
              {renderMemoryPanel()}
            </Drawer>
          ) : (
            <aside className="memory">
              <h2>故事记忆</h2>
              {renderMemoryPanel()}
            </aside>
          )}
        </div>
      ) : activeView === 'memory' ? (
        <MemoryCenter memory={memory} extracting={extracting} onBuildMemory={() => void handleBuildMemory()} />
      ) : activeView === 'novels' ? (
        <ComingSoon title="我的小说" />
      ) : (
        <SettingsView />
      )}
    </AppShell>
  )
}
