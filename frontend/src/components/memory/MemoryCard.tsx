import type { CharacterView } from '../../types'

function factLabel(fact: {
  attribute: string
  value: string
  targetCharacter: string | null
  sourceChapter: number
}): string {
  const value = fact.targetCharacter
    ? `与${fact.targetCharacter}的关系=${fact.value}`
    : `${fact.attribute}=${fact.value}`
  return `${value}（第${fact.sourceChapter + 1}章）`
}

/** 人物记忆卡：当前状态 + 历史（可折叠）。工作台面板与记忆中心共用。 */
export default function MemoryCard({ character }: { character: CharacterView }) {
  return (
    <div className="char-card">
      <div className="char-name">
        {character.name}
        {character.aliases.length > 0 && (
          <span className="aliases">（{character.aliases.join('、')}）</span>
        )}
        <span className="badge">{character.status}</span>
      </div>
      {character.currentFacts.length > 0 && (
        <ul className="facts">
          {character.currentFacts.map((f, i) => (
            <li key={i}>{factLabel(f)}</li>
          ))}
        </ul>
      )}
      {character.historyFacts.length > 0 && (
        <details>
          <summary>历史（{character.historyFacts.length}）</summary>
          <ul className="facts">
            {character.historyFacts.map((f, i) => (
              <li key={i}>
                {factLabel(f)} → 至第{(f.validUntilChapter ?? 0) + 1}章
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
