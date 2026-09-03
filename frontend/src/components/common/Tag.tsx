import type { ReactNode } from 'react'

type TagVariant = 'default' | 'primary' | 'success' | 'warning' | 'danger'

interface TagProps {
  variant?: TagVariant
  children: ReactNode
}

/** 轻量标签：记忆类型（SUMMARY/FACT/EVENT）、状态、角色标记等。 */
export default function Tag({ variant = 'default', children }: TagProps) {
  return <span className={`tag tag-${variant}`}>{children}</span>
}
