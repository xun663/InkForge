import type { NovelSummary } from '../../types'

interface NovelItemProps {
  novel: NovelSummary
  active: boolean
  onSelect: (id: string) => void
}

/** 单个小说列表项：标题 + 可用信息（章节数）。不显示不存在的数据。 */
export default function NovelItem({ novel, active, onSelect }: NovelItemProps) {
  return (
    <button
      type="button"
      className={`novel-item ${active ? 'active' : ''}`}
      onClick={() => onSelect(novel.id)}
    >
      <span className="novel-title">{novel.title}</span>
      <span className="novel-meta">{novel.chapterCount} 章</span>
    </button>
  )
}
