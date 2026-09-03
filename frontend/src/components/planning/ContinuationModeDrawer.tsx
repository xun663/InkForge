import { useEffect, useState } from 'react'
import Button from '../common/Button'
import Drawer from '../common/Drawer'
import ErrorState from '../common/ErrorState'
import LoadingState from '../common/LoadingState'
import DirectionCards from './DirectionCards'
import EndingPlanPanel from './EndingPlanPanel'
import {
  abandonPlan,
  confirmPlan,
  createPlanFromDirection,
  fetchContinuationOptions,
  fetchPlans,
} from '../../api'
import type { ContinuationMode, PlanDirection, PlanStatus, StoryPlan } from '../../types'

type DrawerView = 'mode-select' | 'directions' | 'ending-plan'

interface ContinuationModeDrawerProps {
  open: boolean
  novelId: string | null
  generating: boolean
  onClose: () => void
  /** 计划确认后启动正式生成（stepIndex 仅 ENDING 有意义）。 */
  onStartGeneration: (mode: ContinuationMode, planId: string, stepIndex: number | null) => void
  /** 旧版直接续写（不使用规划）。 */
  onDirectContinue: () => void
}

const MODE_CARDS: { key: ContinuationMode; icon: string; title: string; desc: string }[] = [
  { key: 'PLOT_CHOICE', icon: '🧭', title: '剧情选择', desc: '决定下一阶段往哪里发展' },
  { key: 'ENDING', icon: '🏁', title: '完结', desc: '逐步收束主线并完成故事' },
  { key: 'EXPANSION', icon: '🌌', title: '拓展', desc: '开启新的剧情和世界' },
]

const MODE_LABEL: Record<ContinuationMode, string> = {
  PLOT_CHOICE: '剧情选择',
  ENDING: '完结',
  EXPANSION: '拓展',
}

const STATUS_LABEL: Record<PlanStatus, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  ABANDONED: '已放弃',
}

function findBlockingPlan(plans: StoryPlan[]): StoryPlan | null {
  return plans.find((p) => p.status === 'CONFIRMED' || p.status === 'IN_PROGRESS') ?? null
}

/**
 * P6：续写方式选择抽屉。点击"开始续写"后先选叙事策略——
 * 剧情选择/拓展 → 候选方向卡片（选择/刷新/自定义）→ 确认生成；
 * 完结 → 分析未解决剧情并给出收束方案 → 确认/修改/重新生成 → 按阶段生成。
 * 所有规划只是候选计划，确认前不产生正文、不写入 Story Memory。
 */
export default function ContinuationModeDrawer({
  open,
  novelId,
  generating,
  onClose,
  onStartGeneration,
  onDirectContinue,
}: ContinuationModeDrawerProps) {
  const [view, setView] = useState<DrawerView>('mode-select')
  const [mode, setMode] = useState<ContinuationMode | null>(null)
  const [directions, setDirections] = useState<PlanDirection[] | null>(null)
  const [selectedDirection, setSelectedDirection] = useState<number | null>(null)
  const [plan, setPlan] = useState<StoryPlan | null>(null)
  const [selectedStep, setSelectedStep] = useState(0)
  const [instruction, setInstruction] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [blockingPlan, setBlockingPlan] = useState<StoryPlan | null>(null)

  useEffect(() => {
    if (!open || !novelId) {
      setBlockingPlan(null)
      return
    }
    let cancelled = false
    fetchPlans(novelId)
      .then((plans) => {
        if (!cancelled) setBlockingPlan(findBlockingPlan(plans))
      })
      .catch(() => {
        if (!cancelled) setBlockingPlan(null)
      })
    return () => {
      cancelled = true
    }
  }, [open, novelId])

  function reset() {
    setView('mode-select')
    setMode(null)
    setDirections(null)
    setSelectedDirection(null)
    setPlan(null)
    setSelectedStep(0)
    setInstruction('')
    setBusy(false)
    setError('')
  }

  function handleClose() {
    onClose()
    // 关闭抽屉后回到模式选择（草稿计划仍在服务端，可重新进入）
    window.setTimeout(reset, 200)
  }

  async function chooseMode(selected: ContinuationMode) {
    if (!novelId || busy) return
    setMode(selected)
    setBusy(true)
    setError('')
    try {
      const result = await fetchContinuationOptions(novelId, {
        mode: selected,
        userInstruction: instruction.trim() || undefined,
      })
      if ('planId' in result) {
        setPlan(result)
        setSelectedStep(0)
        setView('ending-plan')
      } else {
        setDirections(result)
        setSelectedDirection(null)
        setView('directions')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setMode(null)
    } finally {
      setBusy(false)
    }
  }

  async function refreshDirections() {
    if (!novelId || !mode) return
    await chooseMode(mode)
  }

  async function startWithDirection() {
    if (!novelId || !mode || selectedDirection == null || !directions) return
    setBusy(true)
    setError('')
    try {
      const direction = directions[selectedDirection]
      const created = await createPlanFromDirection(novelId, {
        mode,
        direction,
        userInstruction: instruction.trim() || undefined,
      })
      const confirmed = await confirmPlan(novelId, created.planId)
      onStartGeneration(confirmed.mode, confirmed.planId, null)
      handleClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function confirmEndingPlan() {
    if (!novelId || !plan || !mode) return
    setBusy(true)
    setError('')
    try {
      await confirmPlan(novelId, plan.planId)
      onStartGeneration(mode, plan.planId, selectedStep)
      handleClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function handleAbandonBlocking() {
    if (!novelId || !blockingPlan) return
    setBusy(true)
    setError('')
    try {
      await abandonPlan(novelId, blockingPlan.planId)
      setBlockingPlan(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  function handleContinueBlocking() {
    if (!blockingPlan) return
    onStartGeneration(
      blockingPlan.mode,
      blockingPlan.planId,
      blockingPlan.mode === 'ENDING' ? 0 : null,
    )
    handleClose()
  }

  const modeTitle = mode == null ? '选择续写方式'
    : mode === 'PLOT_CHOICE' ? '剧情选择 · 候选方向'
    : mode === 'ENDING' ? '完结 · 收束方案' : '拓展 · 新方向'

  return (
    <Drawer open={open} title="续写方式" onClose={handleClose}>
      {error && <ErrorState message={error} />}
      {busy && <LoadingState />}

      {blockingPlan && (
        <div className="active-plan-banner">
          <p>
            这本书已有一份<strong>{STATUS_LABEL[blockingPlan.status]}</strong>的
            {MODE_LABEL[blockingPlan.mode]}计划
            「{blockingPlan.title || blockingPlan.planId}」。
            一本小说同时只能跟一条规划走，换方向前需要先放弃它。
          </p>
          <div className="plan-actions">
            <Button onClick={handleContinueBlocking} disabled={busy || generating}>
              按此计划继续写
            </Button>
            <Button
              variant="danger"
              onClick={() => void handleAbandonBlocking()}
              disabled={busy || generating}
            >
              放弃当前计划
            </Button>
          </div>
        </div>
      )}

      {view === 'mode-select' && (
        <div className="mode-select">
          <div className="mode-cards">
            {MODE_CARDS.map((card) => (
              <button
                key={card.key}
                type="button"
                className="mode-card"
                disabled={busy || generating || !novelId || blockingPlan != null}
                onClick={() => void chooseMode(card.key)}
              >
                <span className="mode-icon">{card.icon}</span>
                <strong>{card.title}</strong>
                <span className="mode-desc">{card.desc}</span>
              </button>
            ))}
          </div>

          <label className="instruction-field">
            <span>给 AI 的额外要求（可选）</span>
            <textarea
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              rows={2}
              placeholder="例如：节奏快一点 / 多写人物对话 / 侧重某个角色"
            />
          </label>

          <div className="mode-divider">或者</div>
          <Button variant="secondary" onClick={onDirectContinue} disabled={generating || !novelId}>
            ✍️ 直接续写（不使用规划）
          </Button>
        </div>
      )}

      {view === 'directions' && directions && (
        <div className="directions-view">
          <h4 className="view-title">{modeTitle}</h4>
          <DirectionCards
            directions={directions}
            selectedIndex={selectedDirection}
            onSelect={setSelectedDirection}
          />
          <label className="instruction-field">
            <span>本次生成要求（可选，将与所选方向合并）</span>
            <textarea
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              rows={2}
              placeholder="例如：从某个配角视角展开"
            />
          </label>
          <div className="plan-actions">
            <Button onClick={() => void startWithDirection()} disabled={busy || selectedDirection == null}>
              {busy ? '处理中……' : '开始这个方向'}
            </Button>
            <Button variant="secondary" onClick={() => void refreshDirections()} disabled={busy}>
              🔄 刷新方向
            </Button>
            <Button variant="ghost" onClick={() => setView('mode-select')} disabled={busy}>
              返回
            </Button>
          </div>
        </div>
      )}

      {view === 'ending-plan' && plan && (
        <div className="ending-view">
          <h4 className="view-title">{modeTitle}</h4>
          <label className="instruction-field">
            <span>修改方案要求（可选，重新生成时生效）</span>
            <textarea
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              rows={2}
              placeholder="例如：保留某条支线 / 结局不要悲剧"
            />
          </label>
          <EndingPlanPanel
            plan={plan}
            selectedStep={selectedStep}
            onSelectStep={setSelectedStep}
            onConfirm={() => void confirmEndingPlan()}
            onRegenerate={() => void refreshDirections()}
            busy={busy}
          />
          <div className="plan-actions">
            <Button variant="ghost" onClick={() => setView('mode-select')} disabled={busy}>
              返回
            </Button>
          </div>
        </div>
      )}
    </Drawer>
  )
}
