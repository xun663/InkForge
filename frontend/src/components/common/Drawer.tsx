import { useEffect, useRef, type ReactNode } from 'react'

interface DrawerProps {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
}

/**
 * 右侧抽屉（Overlay）：窄屏下承载 Memory / Trace 等辅助面板。
 *
 * - 保持子组件常驻挂载（CSS 隐藏而非卸载），依赖其中的组件状态（如 Trace 缓存）。
 * - Escape / backdrop 点击关闭；打开时锁定底层滚动；关闭按钮自动聚焦。
 * - 纯 CSS + React，无第三方库。
 */
export default function Drawer({ open, title, onClose, children }: DrawerProps) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = prev
      window.removeEventListener('keydown', onKey)
    }
  }, [open, onClose])

  return (
    <div className={`drawer-layer ${open ? 'open' : 'closed'}`} aria-hidden={!open}>
      <div className="drawer-backdrop" onClick={onClose} />
      <div className="drawer" role="dialog" aria-modal="true" aria-label={title}>
        <header className="drawer-head">
          <h3>{title}</h3>
          <button
            ref={closeRef}
            type="button"
            className="drawer-close"
            onClick={onClose}
            aria-label="关闭"
          >
            ✕
          </button>
        </header>
        <div className="drawer-body">{children}</div>
      </div>
    </div>
  )
}
