import { useEffect, useState } from 'react'
import { getRetrievalTrace } from '../../api'
import type { RetrievalTrace } from '../../types'
import RetrievalOverview from './RetrievalOverview'
import RetrievalStageCard, { type StageDefinition } from './RetrievalStageCard'

/** 技术层阶段顺序与分数尺度（各阶段尺度不同，展示时严格区分）。 */
const STAGES: StageDefinition[] = [
  { key: 'bm25', label: 'BM25', scaleLabel: '关键词匹配得分', description: '根据关键词匹配程度打分。' },
  { key: 'vector', label: 'Vector', scaleLabel: '语义相似度', description: '根据向量空间中的语义接近程度打分。' },
  { key: 'fusion', label: 'RRF Fusion', scaleLabel: '融合排序分', description: '合并多个检索结果的排名。' },
  { key: 'rerank', label: 'Reranker', scaleLabel: '重排分', description: '在候选基础上再次排序。' },
  { key: 'final', label: 'Final', scaleLabel: '最终得分', description: '最终进入续写上下文的记忆。', showAll: true },
]

/**
 * P4-UI-D：AI 检索过程面板（渐进式信息披露）。
 * 第一层（用户层）：方向 + 流水线，不暴露专业术语；
 * 第二层（技术层）：「查看详细检索过程」折叠展开 BM25/Vector/RRF/Reranker/Final。
 *
 * 始终挂载（CSS 隐藏而非卸载），trace 缓存在组件 state——再次打开不重复请求；
 * traceId 改变时重新请求；Trace 失败/为空绝不影响已生成的正文。
 */
export default function RetrievalTracePanel({
  novelId,
  traceId,
  visible,
}: {
  novelId: string
  traceId: string
  visible: boolean
}) {
  const [trace, setTrace] = useState<RetrievalTrace | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [techOpen, setTechOpen] = useState(false)

  // fetch once per traceId; keep the cached trace across open/close (component stays mounted)
  useEffect(() => {
    if (!visible) return
    if (trace && trace.id === traceId) return // cached
    let cancelled = false
    setLoading(true)
    setError('')
    setTechOpen(false)
    getRetrievalTrace(novelId, traceId)
      .then((result) => {
        if (cancelled) return
        setTrace(result)
      })
      .catch(() => {
        if (cancelled) return
        setError('检索记录加载失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, traceId, novelId])

  if (!visible) return null

  const hasPipeline = trace != null && Object.keys(trace.pipeline).length > 0
  const finalCount = new Set((trace?.pipeline.final ?? []).map((r) => r.chunkId)).size

  return (
    <div className="trace-panel">
      <h3>AI 检索过程</h3>

      {loading && <p className="trace-note trace-loading">正在读取 AI 的检索过程……</p>}
      {error && <p className="trace-note trace-error">{error}</p>}
      {!loading && !error && trace === null && (
        <p className="trace-note">检索记录不存在或已失效。</p>
      )}
      {!loading && !error && trace && !hasPipeline && (
        <p className="trace-note">本次没有可展示的检索记录。</p>
      )}

      {!loading && !error && trace && hasPipeline && (
        <>
          <p className="trace-intro">本次续写根据当前剧情，从故事记忆中找到 {finalCount} 条相关内容。</p>
          <RetrievalOverview trace={trace} />

          <button className="trace-tech-toggle" onClick={() => setTechOpen((prev) => !prev)} type="button">
            查看详细检索过程 {techOpen ? '▾' : '▸'}
          </button>

          {techOpen && (
            <div className="trace-tech">
              {STAGES.filter((s) => (trace.pipeline[s.key] ?? []).length > 0).map((s) => (
                <RetrievalStageCard key={s.key} stage={s} results={trace.pipeline[s.key] ?? []} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}
