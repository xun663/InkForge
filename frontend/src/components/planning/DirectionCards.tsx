import type { PlanDirection } from '../../types'

interface DirectionCardsProps {
  directions: PlanDirection[]
  selectedIndex: number | null
  onSelect: (index: number) => void
}

/**
 * P6：候选剧情方向卡片（PLOT_CHOICE / EXPANSION 共用）。
 * possibleConflict / newConflict 由后端 PlanDirection.conflict() 语义决定，
 * 前端取非空字段展示；方向只是候选，不是既定事实。
 */
export default function DirectionCards({ directions, selectedIndex, onSelect }: DirectionCardsProps) {
  return (
    <div className="direction-cards">
      {directions.map((direction, index) => {
        const conflict = direction.possibleConflict || direction.newConflict || ''
        return (
          <button
            key={index}
            type="button"
            role="radio"
            aria-checked={selectedIndex === index}
            className={`direction-card ${selectedIndex === index ? 'selected' : ''}`}
            onClick={() => onSelect(index)}
          >
            <h4>{direction.title}</h4>
            <p className="direction-summary">{direction.summary}</p>
            {direction.rationale && <p className="direction-line">💡 {direction.rationale}</p>}
            {direction.involvedCharacters.length > 0 && (
              <p className="direction-line">👥 {direction.involvedCharacters.join('、')}</p>
            )}
            {conflict && <p className="direction-line">⚔️ {conflict}</p>}
            {direction.directionGoal && <p className="direction-line goal">🎯 {direction.directionGoal}</p>}
          </button>
        )
      })}
    </div>
  )
}
