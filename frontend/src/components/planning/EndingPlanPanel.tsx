import Button from '../common/Button'
import Tag from '../common/Tag'
import type { PlanStep, StoryPlan } from '../../types'

interface EndingPlanPanelProps {
  plan: StoryPlan
  selectedStep: number
  onSelectStep: (index: number) => void
  onConfirm: () => void
  onRegenerate: () => void
  busy: boolean
}

/**
 * P6：完结方案面板 —— 展示未解决剧情/人物弧/伏笔/收束阶段，
 * 用户可以确认方案（从选定阶段开始生成）、修改要求后重新生成。
 * 方案是规划数据：确认前不会产生任何正文，也不会写入 Story Memory。
 */
export default function EndingPlanPanel({
  plan,
  selectedStep,
  onSelectStep,
  onConfirm,
  onRegenerate,
  busy,
}: EndingPlanPanelProps) {
  const analysis = plan.analysis

  return (
    <div className="plan-panel">
      <div className="plan-head">
        <h4>{plan.title || '完结方案'}</h4>
        <Tag variant={plan.status === 'DRAFT' ? 'warning' : 'primary'}>{plan.status}</Tag>
      </div>

      {analysis && (
        <div className="plan-analysis">
          {analysis.mainArc && <p className="direction-line">🧵 主线：{analysis.mainArc}</p>}
          {analysis.worldState && <p className="direction-line">🌍 世界：{analysis.worldState}</p>}
          {analysis.finalConflict && <p className="direction-line">⚔️ 最终冲突：{analysis.finalConflict}</p>}
          {analysis.endingDirection && <p className="direction-line">🏁 结局方向：{analysis.endingDirection}</p>}
          {analysis.characterArcs.length > 0 && (
            <p className="direction-line">
              👥 人物弧：{analysis.characterArcs.map((a) => `${a.name}（${a.arc}）`).join('；')}
            </p>
          )}
          {analysis.foreshadowing.length > 0 && (
            <p className="direction-line">🔮 伏笔：{analysis.foreshadowing.join('；')}</p>
          )}
          {analysis.droppableSubplots.length > 0 && (
            <p className="direction-line">✂️ 可舍弃支线：{analysis.droppableSubplots.join('；')}</p>
          )}
        </div>
      )}

      {plan.steps.length > 0 && (
        <div className="plan-steps">
          <h5>收束阶段（选择从哪个阶段开始生成）</h5>
          <ol>
            {plan.steps.map((step: PlanStep) => (
              <li key={step.index}>
                <button
                  type="button"
                  className={`plan-step ${selectedStep === step.index ? 'selected' : ''}`}
                  onClick={() => onSelectStep(step.index)}
                >
                  <strong>{step.title}</strong>
                  {step.summary && <span> —— {step.summary}</span>}
                  {step.phaseGoal && <em>（{step.phaseGoal}）</em>}
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}

      <div className="plan-actions">
        <Button onClick={onConfirm} disabled={busy}>
          {busy ? '处理中……' : `确认方案 · 从第 ${selectedStep + 1} 阶段开始生成`}
        </Button>
        <Button variant="secondary" onClick={onRegenerate} disabled={busy}>
          重新生成方案
        </Button>
      </div>
    </div>
  )
}
