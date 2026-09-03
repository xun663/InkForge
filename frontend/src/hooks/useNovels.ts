import { useCallback, useState } from 'react'
import { uploadNovel } from '../api'
import type { NovelSummary } from '../types'

/**
 * 小说列表状态（会话内）。
 *
 * 已知后端缺口：`GET /api/novels`（列表端点）当前不存在——只有上传 / 单查 / 章节 / 断点。
 * 因此本 hook 维护"本次会话已导入"的本地列表；跨会话持久列表需后端增量（已记录，不在此实现）。
 */
export function useNovels() {
  const [novels, setNovels] = useState<NovelSummary[]>([])
  const [activeNovelId, setActiveNovelId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  /** 导入小说：上传成功 → 加入列表 → 设为当前。返回导入结果。 */
  const importNovel = useCallback(async (file: File): Promise<NovelSummary> => {
    setLoading(true)
    setError('')
    try {
      const summary = await uploadNovel(file)
      setNovels((prev) => {
        const without = prev.filter((n) => n.id !== summary.id)
        return [...without, summary]
      })
      setActiveNovelId(summary.id)
      return summary
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      throw e
    } finally {
      setLoading(false)
    }
  }, [])

  const selectNovel = useCallback((id: string) => {
    setActiveNovelId(id)
    setError('')
  }, [])

  const activeNovel = novels.find((n) => n.id === activeNovelId) ?? null

  return { novels, activeNovel, activeNovelId, loading, error, importNovel, selectNovel }
}
