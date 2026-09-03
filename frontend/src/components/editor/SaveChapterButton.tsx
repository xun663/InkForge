import { useEffect, useState } from 'react'
import Button from '../common/Button'
import ErrorState from '../common/ErrorState'
import { saveChapter } from '../../api'
import type { ChapterCreated } from '../../types'

interface SaveChapterButtonProps {
  novelId: string | null
  content: string
  disabled?: boolean
  onSaved?: (created: ChapterCreated) => void
}

/**
 * P6：把本次生成的续写草稿保存为正式章节。
 * 边界提示（对齐系统约束）：保存只让文本成为 Canon（小说正文的一部分），
 * 不会自动写入 Story Memory——记忆提取仍需用户显式构建。
 */
export default function SaveChapterButton({ novelId, content, disabled, onSaved }: SaveChapterButtonProps) {
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState<ChapterCreated | null>(null)
  const [error, setError] = useState('')

  // 新一轮生成内容到来 → 重置保存状态
  useEffect(() => {
    setSaved(null)
    setError('')
  }, [content])

  async function handleSave() {
    if (!novelId || saving || !content.trim()) return
    setSaving(true)
    setError('')
    try {
      const created = await saveChapter(novelId, { content })
      setSaved(created)
      onSaved?.(created)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="save-chapter">
      <Button
        variant="secondary"
        size="sm"
        disabled={disabled || saving || !novelId || !content.trim()}
        onClick={() => void handleSave()}
      >
        {saved ? '✓ 已保存为正式章节' : saving ? '保存中……' : '💾 保存为正式章节'}
      </Button>
      {saved && (
        <span className="meta">
          已存为第 {saved.ordinal + 1} 章；记忆提取仍需显式构建，不会自动更新
        </span>
      )}
      {error && <ErrorState message={error} />}
    </div>
  )
}
