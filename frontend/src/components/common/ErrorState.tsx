interface ErrorStateProps {
  message?: string
  retry?: () => void
}

/** 统一错误状态：API 错误 / Trace 加载错误 / 页面局部错误。不抛出全局错误。 */
export default function ErrorState({ message = '出错了，请稍后重试', retry }: ErrorStateProps) {
  return (
    <div className="error-state" role="alert">
      <p className="error-message">{message}</p>
      {retry && (
        <button className="btn btn-secondary btn-sm" onClick={retry} type="button">
          重试
        </button>
      )}
    </div>
  )
}
