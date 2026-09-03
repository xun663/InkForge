interface LoadingStateProps {
  label?: string
}

/** 统一加载状态：上传 / 提取 / 章节 / Trace。 */
export default function LoadingState({ label = '加载中……' }: LoadingStateProps) {
  return (
    <div className="loading-state" role="status">
      <span className="loading-spinner" aria-hidden="true" />
      <span className="loading-label">{label}</span>
    </div>
  )
}
