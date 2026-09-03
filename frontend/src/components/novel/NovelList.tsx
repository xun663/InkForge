import { useRef } from 'react'
import type { NovelSummary } from '../../types'
import Button from '../common/Button'
import EmptyState from '../common/EmptyState'
import LoadingState from '../common/LoadingState'
import ErrorState from '../common/ErrorState'
import NovelItem from './NovelItem'

interface NovelListProps {
  novels: NovelSummary[]
  activeNovelId: string | null
  loading: boolean
  error: string
  disabled: boolean
  onSelect: (id: string) => void
  onImport: (file: File) => void
}

/** 我的小说：作品列表 + 导入入口（写作软件式，非后台表格）。 */
export default function NovelList({
  novels,
  activeNovelId,
  loading,
  error,
  disabled,
  onSelect,
  onImport,
}: NovelListProps) {
  const fileInputRef = useRef<HTMLInputElement>(null)

  return (
    <section className="novel-list">
      <div className="rail-head">
        <h3>我的小说</h3>
        <Button
          variant="ghost"
          size="sm"
          disabled={disabled || loading}
          onClick={() => fileInputRef.current?.click()}
        >
          + 导入
        </Button>
        <input
          ref={fileInputRef}
          type="file"
          accept=".txt,text/plain"
          hidden
          disabled={disabled || loading}
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) onImport(file)
            e.target.value = ''
          }}
        />
      </div>

      {loading && <LoadingState label="导入中……" />}
      {error && <ErrorState message={error} />}
      {!loading && !error && novels.length === 0 && (
        <EmptyState title="还没有小说" message="点击「+ 导入」上传 TXT 小说" />
      )}
      {novels.length > 0 && (
        <ul className="novel-items">
          {novels.map((novel) => (
            <li key={novel.id}>
              <NovelItem novel={novel} active={novel.id === activeNovelId} onSelect={onSelect} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
