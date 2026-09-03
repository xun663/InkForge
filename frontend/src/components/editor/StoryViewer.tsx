import type { Breakpoint, Chapter, LastChapter, NovelSummary } from '../../types'
import EmptyState from '../common/EmptyState'
import GenerationBar from './GenerationBar'

interface StoryViewerProps {
  novel: NovelSummary
  chapters: Chapter[]
  selectedOrdinal: number
  lastChapter: LastChapter | null
  breakpoint: Breakpoint | null
  generating: boolean
  onContinue: () => void
}

function chapterLabel(chapter: { chapterNo: number | null; title: string }): string {
  return chapter.chapterNo != null ? `第${chapter.chapterNo}章` : chapter.title || '（无标题）'
}

/**
 * 正文阅读区：当前选中章节的正文展示。
 * - 最后一章：复用 fetchLastChapter 全文（真实数据）
 * - 其他章节：后端暂无章节全文 API → 展示元信息 + 明确提示，不伪造正文
 * 续写操作（GenerationBar）挂在本区底部。
 */
export default function StoryViewer({
  novel,
  chapters,
  selectedOrdinal,
  lastChapter,
  breakpoint,
  generating,
  onContinue,
}: StoryViewerProps) {
  const selected = chapters.find((c) => c.ordinal === selectedOrdinal) ?? null
  const isLast = selected != null && lastChapter != null && selected.ordinal === lastChapter.ordinal
  const breakpointOrdinal = breakpoint?.chapterOrdinal ?? selectedOrdinal

  return (
    <section className="story-viewer">
      <header className="story-header">
        <h2>{novel.title}</h2>
        <span className="story-chapter">
          {selected ? chapterLabel(selected) : '未选择章节'}
          {selected && !isLast && '（查看中）'}
        </span>
      </header>

      {!selected && <EmptyState title="选择章节开始阅读" />}

      {selected && isLast && lastChapter && (
        <article className="story-content">
          <p className="story-note">本章为最后一章 · 当前断点</p>
          <pre className="story-text">{lastChapter.content}</pre>
        </article>
      )}

      {selected && !isLast && (
        <article className="story-content">
          <EmptyState
            title={`${chapterLabel(selected)} · ${selected.charCount} 字`}
            message="当前接口暂未提供该章节全文，可在工作台查看断点后继续续写"
          />
        </article>
      )}

      {breakpoint && (
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

      <GenerationBar
        generating={generating}
        disabled={!novel}
        onContinue={onContinue}
        memoryHint={breakpointOrdinal}
      />
    </section>
  )
}
