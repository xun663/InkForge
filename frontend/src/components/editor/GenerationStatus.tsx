import type { DoneMeta } from '../../types'
import Button from '../common/Button'
import Drawer from '../common/Drawer'
import ErrorState from '../common/ErrorState'
import EmptyState from '../common/EmptyState'
import RetrievalTracePanel from '../retrieval/RetrievalTracePanel'
import { useMediaQuery } from '../../hooks/useMediaQuery'

export type GenStage = 'idle' | 'ready' | 'generating' | 'done'

interface GenerationStatusProps {
  stage: GenStage
  output: string
  doneMeta: DoneMeta | null
  error: string
  novelId: string | null
  showTrace: boolean
  onToggleTrace: (value?: boolean) => void
}

/**
 * 生成状态区：streaming 输出、usage、done 元信息、Trace 入口与面板。
 * 复用现有 streamContinuation 链路（token/done/error 解析语义不变）。
 * 窄屏（≤820px）Trace 以 Drawer/Overlay 呈现；宽屏内联。Trace 面板常驻挂载以保缓存。
 */
export default function GenerationStatus({
  stage,
  output,
  doneMeta,
  error,
  novelId,
  showTrace,
  onToggleTrace,
}: GenerationStatusProps) {
  const compact = useMediaQuery('(max-width: 820px)')
  const hasTrace = doneMeta != null && doneMeta.retrievalTraceId != null && (doneMeta.retrievedCount ?? 0) > 0
  const genState = stage === 'generating' ? 'AI 正在生成…' : stage === 'done' ? '生成完成' : ''

  const tracePanel =
    hasTrace && novelId && doneMeta ? (
      <RetrievalTracePanel
        novelId={novelId}
        traceId={doneMeta.retrievalTraceId ?? ''}
        visible={showTrace}
      />
    ) : null

  return (
    <section className="panel generation-status">
      <h2>
        AI 续写
        {genState && <span className={`gen-state ${stage === 'generating' ? 'streaming' : ''}`}>{genState}</span>}
        {stage === 'generating' && <span className="cursor">▍</span>}
      </h2>

      <pre className="output">
        {output ||
          (stage === 'generating' ? '' : '（点击"开始续写"后，生成内容将在此流式显示）')}
      </pre>

      {doneMeta && (
        <footer className="usage">
          input {doneMeta.promptTokens.toLocaleString()} · output{' '}
          {doneMeta.completionTokens.toLocaleString()} · total {doneMeta.totalTokens.toLocaleString()}{' '}
          tokens · {(doneMeta.latencyMs / 1000).toFixed(1)}s · ≈$
          {doneMeta.estimatedCostUsd.toFixed(4)} · {doneMeta.provider}/{doneMeta.model}
        </footer>
      )}

      {error && <ErrorState message={error} />}

      {hasTrace && novelId && doneMeta && (
        <>
          <Button variant="secondary" className="trace-toggle" onClick={() => onToggleTrace()}>
            📚 参考了 {doneMeta.retrievedCount} 条记忆 · 查看检索过程 {showTrace ? '▾' : '▸'}
          </Button>
          {compact ? (
            <Drawer open={showTrace} title="AI 检索过程" onClose={() => onToggleTrace(false)}>
              {tracePanel}
            </Drawer>
          ) : (
            tracePanel
          )}
        </>
      )}

      {!hasTrace && stage === 'done' && (
        <div className="trace-note">
          <EmptyState title="本次续写没有检索记忆" message="未生成记忆时直接基于最近章节续写" />
        </div>
      )}
    </section>
  )
}
