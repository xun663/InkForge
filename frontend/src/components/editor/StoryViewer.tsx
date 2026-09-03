import { useEffect, useRef } from 'react'
import type { Breakpoint, Chapter, LastChapter, NovelSummary } from '../../types'
import EmptyState from '../common/EmptyState'
import Button from '../common/Button'
import ErrorState from '../common/ErrorState'
import GenerationBar from './GenerationBar'

interface StoryViewerProps {
  novel: NovelSummary
  chapters: Chapter[]
  selectedOrdinal: number
  selectedChapter: LastChapter | null
  lastChapter: LastChapter | null
  breakpoint: Breakpoint | null
  generating: boolean
  loading?: boolean
  loadError?: string
  onSelectChapter: (ordinal: number) => void
  onContinue: () => void
}

function chapterLabel(chapter: { chapterNo: number | null; title: string }): string {
  return chapter.chapterNo != null ? `第${chapter.chapterNo}章` : chapter.title || '（无标题）'
}

/**
 * 正文阅读区：点选哪一章就显示哪一章全文。续写仍从最新断点发起。
 */
export default function StoryViewer({
  novel,
  chapters,
  selectedOrdinal,
  selectedChapter,
  lastChapter,
  breakpoint,
  generating,
  loading = false,
  loadError = '',
  onSelectChapter,
  onContinue,
}: StoryViewerProps) {
  const textRef = useRef<HTMLPreElement>(null)
  const selected = chapters.find((c) => c.ordinal === selectedOrdinal) ?? null
  const isLast = selected != null && lastChapter != null && selected.ordinal === lastChapter.ordinal
  const firstOrdinal = chapters[0]?.ordinal ?? 0
  const lastOrdinal = lastChapter?.ordinal ?? chapters[chapters.length - 1]?.ordinal ?? selectedOrdinal
  const body =
    selectedChapter != null && selectedChapter.ordinal === selectedOrdinal ? selectedChapter : null

  useEffect(() => {
    textRef.current?.scrollTo(0, 0)
  }, [selectedOrdinal])

  return (
    <section className="story-viewer">
      <header className="story-header">
        <h2>{novel.title}</h2>
        <span className="story-chapter">
          {selected ? chapterLabel(selected) : '未选择章节'}
        </span>
        <div className="story-nav">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={selected == null || selected.ordinal <= firstOrdinal}
            onClick={() => onSelectChapter(selectedOrdinal - 1)}
          >
            上一章
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={selected == null || selected.ordinal >= lastOrdinal}
            onClick={() => onSelectChapter(selectedOrdinal + 1)}
          >
            下一章
          </Button>
        </div>
      </header>

      {!selected && <EmptyState title="选择章节开始阅读" />}

      {selected && loadError && <ErrorState message={loadError} />}

      {selected && !body && !loadError && (
        <article className="story-content">
          <EmptyState
            title={`${chapterLabel(selected)} · ${selected.charCount} 字`}
            message={loading ? '正在读取章节全文……' : '无法显示本章正文'}
          />
        </article>
      )}

      {selected && body && (
        <article className="story-content">
          {isLast && <p className="story-note">本章为最后一章 · 当前断点</p>}
          {!isLast && lastChapter && (
            <p className="story-note">正在阅读历史章节 · 续写仍从{chapterLabel(lastChapter)}断点开始</p>
          )}
          <pre ref={textRef} className="story-text">
            {body.content}
          </pre>
        </article>
      )}

      {isLast && breakpoint && (
        <div className="story-breakpoint">
          <h3>
            当前断点
            <span className="bp-meta">
              {chapterLabel({ chapterNo: breakpoint.chapterNo, title: breakpoint.chapterTitle })}
            </span>
          </h3>
          <pre className="bp-text">{breakpoint.tailExcerpt}</pre>
        </div>
      )}

      {isLast ? (
        <GenerationBar
          generating={generating}
          disabled={!novel}
          onContinue={onContinue}
          memoryHint={breakpoint?.chapterOrdinal ?? selectedOrdinal}
        />
      ) : (
        lastChapter != null && (
          <div className="generation-bar">
            <Button type="button" variant="secondary" size="sm" onClick={() => onSelectChapter(lastChapter.ordinal)}>
              返回断点（{chapterLabel(lastChapter)}）
            </Button>
          </div>
        )
      )}
    </section>
  )
}
