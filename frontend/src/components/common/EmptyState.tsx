import type { ReactNode } from 'react'

interface EmptyStateProps {
  title?: string
  message?: string
  action?: ReactNode
}

/** 统一空状态：没有小说 / 没有章节 / 没有记忆 / 没有检索结果 / 功能占位。 */
export default function EmptyState({ title = '暂无内容', message, action }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <p className="empty-title">{title}</p>
      {message && <p className="empty-message">{message}</p>}
      {action && <div className="empty-action">{action}</div>}
    </div>
  )
}
