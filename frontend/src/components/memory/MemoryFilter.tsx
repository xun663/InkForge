export type MemoryTab = 'all' | 'characters' | 'events' | 'threads' | 'summaries'

const TABS: { key: MemoryTab; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'characters', label: '人物' },
  { key: 'events', label: '事件' },
  { key: 'threads', label: '线索' },
  { key: 'summaries', label: '摘要' },
]

interface MemoryFilterProps {
  tab: MemoryTab
  query: string
  onTabChange: (tab: MemoryTab) => void
  onQueryChange: (query: string) => void
}

/**
 * 记忆过滤：类型 Tab + 本地关键词搜索。
 * 已知后端缺口：无记忆搜索 API —— 搜索为前端本地过滤（不伪造后端能力）。
 */
export default function MemoryFilter({ tab, query, onTabChange, onQueryChange }: MemoryFilterProps) {
  return (
    <div className="memory-filter">
      <input
        className="memory-search"
        type="search"
        placeholder="🔍 搜索记忆（本地过滤）"
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
      />
      <div className="memory-tabs" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            className={`memory-tab ${tab === t.key ? 'active' : ''}`}
            onClick={() => onTabChange(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>
    </div>
  )
}
