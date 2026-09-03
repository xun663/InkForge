import { useState } from 'react'
import Tag from '../common/Tag'
import type { RetrievalTrace, TraceRetrievalResult } from '../../types'

/** 技术层单阶段定义：展示名、分数尺度、阶段说明。 */
export interface StageDefinition {
  key: keyof RetrievalTrace['pipeline']
  label: string
  scaleLabel: string
  description: string
  /** final 阶段默认展示全部（Final 即进入上下文的记忆，量少）。 */
  showAll?: boolean
}

const TYPE_LABEL: Record<TraceRetrievalResult['memoryType'], string> = {
  SUMMARY: '摘要',
  FACT: '人物事实',
  EVENT: '剧情事件',
}

const TYPE_VARIANT: Record<
  TraceRetrievalResult['memoryType'],
  'default' | 'primary' | 'success' | 'warning' | 'danger'
> = {
  SUMMARY: 'default',
  FACT: 'primary',
  EVENT: 'warning',
}

const DEFAULT_TOP = 5

interface RetrievalStageCardProps {
  stage: StageDefinition
  results: TraceRetrievalResult[]
}

/**
 * 技术层单阶段卡片：阶段名 + 分数尺度 + Top 5 / 展开更多 + 高级详情。
 * score 只在本阶段内可比——卡片内固定提示，避免跨阶段误导。
 */
export default function RetrievalStageCard({ stage, results }: RetrievalStageCardProps) {
  const [showAll, setShowAll] = useState(false)
  const visible = stage.showAll || showAll ? results : results.slice(0, DEFAULT_TOP)
  const hidden = results.length - visible.length

  return (
    <div className="stage-card">
      <header className="stage-card-head">
        <span className="stage-card-label">{stage.label}</span>
        <span className="stage-card-scale">{stage.scaleLabel}</span>
        <span className="stage-card-count">{results.length} 条</span>
      </header>

      <p className="stage-card-desc">{stage.description}</p>

      <ul className="stage-card-results">
        {visible.map((r, i) => (
          <li key={`${r.chunkId}-${i}`} className="trace-result">
            <div className="result-main">
              <span className="result-chapter">第{r.chapterOrdinal + 1}章</span>
              <Tag variant={TYPE_VARIANT[r.memoryType]}>{TYPE_LABEL[r.memoryType]}</Tag>
            </div>
            <p className="result-text">{r.text}</p>
            <details className="result-details">
              <summary>高级详情</summary>
              <dl className="result-advance">
                <div>
                  <dt>{stage.scaleLabel}</dt>
                  <dd>{r.score.toFixed(4)}</dd>
                </div>
                <div>
                  <dt>类型</dt>
                  <dd>{r.memoryType}</dd>
                </div>
                <div>
                  <dt>chunkId</dt>
                  <dd>{r.chunkId}</dd>
                </div>
                <div>
                  <dt>sourceId</dt>
                  <dd>{r.sourceId}</dd>
                </div>
                <div>
                  <dt>novelId</dt>
                  <dd>{r.novelId}</dd>
                </div>
              </dl>
            </details>
          </li>
        ))}
        {hidden > 0 && (
          <li>
            <button className="more-link" onClick={() => setShowAll(true)} type="button">
              展开更多（{hidden} 条）
            </button>
          </li>
        )}
      </ul>

      <p className="stage-scale-note">分数仅用于本阶段内排序，不同阶段不可直接比较。</p>
    </div>
  )
}
