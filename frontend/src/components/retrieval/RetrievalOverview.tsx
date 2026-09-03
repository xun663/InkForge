import type { RetrievalTrace } from '../../types'

/** 检索方向定义：按下标映射 QueryBuilder 固定顺序 primary→character→thread。 */
const DIRECTIONS: { label: string; desc: string }[] = [
  { label: '主线剧情', desc: '根据当前章节内容寻找相关的历史剧情。' },
  { label: '当前人物', desc: '寻找与当前人物相关的重要记忆。' },
  { label: '未解决线索', desc: '寻找与当前未解决故事线相关的历史信息。' },
]

interface RetrievalOverviewProps {
  trace: RetrievalTrace
}

/**
 * 用户层展示（P4-UI-D）：不暴露 BM25/Vector/RRF 等术语。
 * 回答「为什么需要这些记忆 / 从哪些方向寻找 / 找到多少候选 / 最终用了哪些」。
 * 所有数量来自 trace 真实数据，不硬编码。
 */
export default function RetrievalOverview({ trace }: RetrievalOverviewProps) {
  const bm25 = trace.pipeline.bm25 ?? []
  const vector = trace.pipeline.vector ?? []
  const finalResults = trace.pipeline.final ?? []

  // 候选池 = BM25 ∪ Vector 去重；最终参考 = Final 去重（后端已去重，此处再保险一次）
  const candidateCount = new Set([...bm25, ...vector].map((r) => r.chunkId)).size
  const finalCount = new Set(finalResults.map((r) => r.chunkId)).size

  return (
    <div className="trace-overview">
      <section className="trace-directions">
        <h4>检索方向</h4>
        {trace.queries.map((q, i) => {
          const dir = DIRECTIONS[i] ?? { label: `方向 ${i + 1}`, desc: '与本次续写相关的历史信息。' }
          return (
            <div key={i} className="direction-card">
              <div className="direction-label">{dir.label}</div>
              <p className="direction-desc">{dir.desc}</p>
              <details className="direction-query">
                <summary>查看查询内容</summary>
                <p className="query-text">{q}</p>
              </details>
            </div>
          )
        })}
      </section>

      <section className="trace-pipeline">
        <h4>检索流程</h4>
        <ol className="pipe-steps">
          <li className="pipe-step">
            <span className="pipe-num">①</span>
            <div className="pipe-body">
              <span className="pipe-title">找到相关记忆</span>
              <span className="pipe-sub">{candidateCount} 条候选</span>
            </div>
          </li>
          <li className="pipe-arrow">↓</li>
          <li className="pipe-step">
            <span className="pipe-num">②</span>
            <div className="pipe-body">
              <span className="pipe-title">综合多个检索结果</span>
              <span className="pipe-sub">结合关键词和语义匹配</span>
            </div>
          </li>
          <li className="pipe-arrow">↓</li>
          <li className="pipe-step pipe-final">
            <span className="pipe-num">③</span>
            <div className="pipe-body">
              <span className="pipe-title">最终参考</span>
              <span className="pipe-sub">{finalCount} 条记忆进入续写上下文</span>
            </div>
          </li>
        </ol>
      </section>
    </div>
  )
}
