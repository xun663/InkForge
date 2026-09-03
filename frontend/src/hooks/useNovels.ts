import { useCallback, useEffect, useState } from 'react'
import { listNovels, uploadNovel } from '../api'
import type { NovelSummary } from '../types'

/**
 * 小说列表：启动时拉 GET /api/novels（含服务端重放导入的蛊真人），上传成功后再并入。
 */
export function useNovels() {
  const [novels, setNovels] = useState<NovelSummary[]>([])
  const [activeNovelId, setActiveNovelId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listNovels()
      .then((items) => {
        if (cancelled) return
        setNovels(items)
        setActiveNovelId((current) => {
          if (current && items.some((n) => n.id === current)) return current
          const preferred = items.reduce<NovelSummary | null>(
            (best, n) => (best == null || n.chapterCount > best.chapterCount ? n : best),
            null,
          )
          return preferred?.id ?? null
        })
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

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
