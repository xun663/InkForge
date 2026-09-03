import { useEffect, useMemo, useRef, useState } from 'react'
import type { Chapter } from '../../types'
import EmptyState from '../common/EmptyState'
import Button from '../common/Button'
import ErrorState from '../common/ErrorState'
import { exportChapters } from '../../api'

interface ChapterListProps {
  chapters: Chapter[]
  activeOrdinal: number | null
  novelId: string | null
  onSelect: (ordinal: number) => void
}

function displayNo(chapter: Chapter): number {
  return chapter.chapterNo ?? chapter.ordinal + 1
}

function chapterLabel(chapter: { chapterNo: number | null; title: string }): string {
  return chapter.chapterNo != null ? `第${chapter.chapterNo}章` : chapter.title || '（无标题）'
}

/** 章节列表：点击阅读；勾选或填范围后导出 TXT。 */
export default function ChapterList({ chapters, activeOrdinal, novelId, onSelect }: ChapterListProps) {
  const lastNo = chapters.length === 0 ? 1 : displayNo(chapters[chapters.length - 1])
  const [fromNo, setFromNo] = useState(String(lastNo))
  const [toNo, setToNo] = useState(String(lastNo))
  const [checked, setChecked] = useState<Set<number>>(() => new Set())
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState('')
  const [jumpNo, setJumpNo] = useState('')
  const listRef = useRef<HTMLUListElement>(null)

  useEffect(() => {
    if (chapters.length === 0) return
    const last = chapters[chapters.length - 1]
    setFromNo(String(displayNo(last)))
    setToNo(String(displayNo(last)))
    setChecked(new Set([last.ordinal]))
    setError('')
    setJumpNo(String(displayNo(last)))
  }, [chapters])

  useEffect(() => {
    const list = listRef.current
    const active = list?.querySelector('.chapter-item.active') as HTMLElement | null
    const scroller = list?.closest('.chapter-list') as HTMLElement | null
    const row = active?.closest('li') as HTMLElement | null
    if (!scroller || !row) return
    const s = scroller.getBoundingClientRect()
    const r = row.getBoundingClientRect()
    if (r.top < s.top || r.bottom > s.bottom) {
      scroller.scrollTop += r.top - s.top - scroller.clientHeight / 3
    }
  }, [activeOrdinal])

  const checkedOrdinals = useMemo(() => {
    const order = new Map(chapters.map((c, i) => [c.ordinal, i]))
    return [...checked].sort((a, b) => (order.get(a) ?? 0) - (order.get(b) ?? 0))
  }, [checked, chapters])

  function toggle(ordinal: number) {
    setChecked((prev) => {
      const next = new Set(prev)
      if (next.has(ordinal)) next.delete(ordinal)
      else next.add(ordinal)
      return next
    })
  }

  function applyRange() {
    const from = Number(fromNo)
    const to = Number(toNo)
    if (!Number.isFinite(from) || !Number.isFinite(to)) return
    const lo = Math.min(from, to)
    const hi = Math.max(from, to)
    setChecked(new Set(chapters.filter((c) => displayNo(c) >= lo && displayNo(c) <= hi).map((c) => c.ordinal)))
  }

  function jumpTo() {
    const n = Number(jumpNo)
    if (!Number.isFinite(n)) return
    const chapter = chapters.find((c) => displayNo(c) === n)
    if (!chapter) {
      setError(`没有第 ${jumpNo} 章`)
      return
    }
    setError('')
    onSelect(chapter.ordinal)
  }

  async function handleExport() {
    if (!novelId || exporting) return
    const ordinals =
      checkedOrdinals.length > 0
        ? checkedOrdinals
        : chapters
            .filter((c) => {
              const n = displayNo(c)
              const from = Number(fromNo)
              const to = Number(toNo)
              return n >= Math.min(from, to) && n <= Math.max(from, to)
            })
            .map((c) => c.ordinal)
    if (ordinals.length === 0) {
      setError('请勾选章节，或填写有效的起止章号')
      return
    }
    setExporting(true)
    setError('')
    try {
      await exportChapters(novelId, ordinals)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setExporting(false)
    }
  }

  return (
    <section className="chapter-list">
      <div className="rail-head">
        <h3>章节</h3>
        {chapters.length > 0 && (
          <span className="chapter-count">{chapters.length}</span>
        )}
      </div>
      {chapters.length > 0 && (
        <form
          className="chapter-jump"
          onSubmit={(e) => {
            e.preventDefault()
            jumpTo()
          }}
        >
          <label>
            跳转到
            <input
              type="number"
              min={1}
              value={jumpNo}
              onChange={(e) => setJumpNo(e.target.value)}
            />
          </label>
          <Button type="submit" variant="ghost" size="sm">
            前往
          </Button>
        </form>
      )}
      {chapters.length === 0 ? (
        <EmptyState title="当前小说还没有可显示的章节" />
      ) : (
        <>
          <ul className="chapter-items" ref={listRef}>
            {chapters.map((c) => (
              <li key={c.ordinal} className="chapter-row">
                <input
                  type="checkbox"
                  checked={checked.has(c.ordinal)}
                  onChange={() => toggle(c.ordinal)}
                  aria-label={`选择${chapterLabel(c)}`}
                />
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
          <div className="chapter-export">
            <div className="chapter-export-range">
              <label>
                从
                <input
                  type="number"
                  min={1}
                  value={fromNo}
                  onChange={(e) => setFromNo(e.target.value)}
                />
              </label>
              <label>
                到
                <input
                  type="number"
                  min={1}
                  value={toNo}
                  onChange={(e) => setToNo(e.target.value)}
                />
              </label>
              <Button type="button" variant="ghost" size="sm" onClick={applyRange}>
                勾选范围
              </Button>
            </div>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={!novelId || exporting}
              onClick={() => void handleExport()}
            >
              {exporting ? '导出中……' : `导出已选 ${checkedOrdinals.length || 0} 章`}
            </Button>
            {error && <ErrorState message={error} />}
          </div>
        </>
      )}
    </section>
  )
}
