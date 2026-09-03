import type { Chapter } from '../../types'
import EmptyState from '../common/EmptyState'

interface ChapterListProps {
  chapters: Chapter[]
  activeOrdinal: number | null
  onSelect: (ordinal: number) => void
}

function chapterLabel(chapter: { chapterNo: number | null; title: string }): string {
  return chapter.chapterNo != null ? `第${chapter.chapterNo}章` : chapter.title || '（无标题）'
}

/** 章节列表：点击选择章节，当前章节高亮。仅展示 API 提供的元数据。 */
export default function ChapterList({ chapters, activeOrdinal, onSelect }: ChapterListProps) {
  return (
    <section className="chapter-list">
      <h3>章节</h3>
      {chapters.length === 0 ? (
        <EmptyState title="当前小说还没有可显示的章节" />
      ) : (
        <ul className="chapter-items">
          {chapters.map((c) => (
            <li key={c.ordinal}>
              <button
                type="button"
                className={`chapter-item ${c.ordinal === activeOrdinal ? 'active' : ''}`}
                onClick={() => onSelect(c.ordinal)}
              >
                <span className="c-title">{chapterLabel(c)}</span>
                <span className="c-chars">{c.charCount} 字</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
