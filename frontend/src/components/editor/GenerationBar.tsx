import Button from '../common/Button'

interface GenerationBarProps {
  generating: boolean
  disabled: boolean
  memoryHint?: number | null
  onContinue: () => void
}

/** 续写操作条：按钮 + 防重复点击 + 生成中状态。 */
export default function GenerationBar({ generating, disabled, memoryHint, onContinue }: GenerationBarProps) {
  return (
    <div className="generation-bar">
      <Button onClick={onContinue} disabled={disabled || generating}>
        {generating ? '生成中……' : '开始续写'}
      </Button>
      {!disabled && memoryHint != null && (
        <span className="hint">将基于「第 {memoryHint + 1} 章」断点继续</span>
      )}
    </div>
  )
}
