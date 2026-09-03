import type { ReactNode } from 'react'

export type AppView = 'workspace' | 'novels' | 'memory' | 'settings'

const NAV_ITEMS: { view: AppView; label: string }[] = [
  { view: 'workspace', label: '工作台' },
  { view: 'novels', label: '我的小说' },
  { view: 'memory', label: '记忆' },
  { view: 'settings', label: '设置' },
]

interface AppShellProps {
  activeView: AppView
  onNavigate: (view: AppView) => void
  children: ReactNode
}

/**
 * 应用外壳：顶栏（产品名 + 状态） + 左侧导航 + 内容区。
 * 导航为状态切换（无路由依赖）；未实现页面由调用方用占位视图呈现。
 */
export default function AppShell({ activeView, onNavigate, children }: AppShellProps) {
  return (
    <div className="app-shell">
      <header className="shell-header">
        <div className="shell-brand">
          <h1>InkForge</h1>
          <span className="shell-subtitle">AI 长篇写作与记忆工具</span>
          <span className="shell-subtitle-en">Long-form AI Writing &amp; Memory</span>
        </div>
      </header>

      <div className="shell-body">
        <nav className="shell-sidebar" aria-label="主导航">
          <ul className="shell-nav">
            {NAV_ITEMS.map((item) => (
              <li key={item.view}>
                <button
                  type="button"
                  className={`shell-nav-item ${activeView === item.view ? 'active' : ''}`}
                  onClick={() => onNavigate(item.view)}
                >
                  {item.label}
                </button>
              </li>
            ))}
          </ul>
        </nav>

        <main className="shell-content">{children}</main>
      </div>
    </div>
  )
}
