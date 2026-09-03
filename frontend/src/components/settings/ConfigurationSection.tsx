import type { ConfigSection } from './settingsData'

/**
 * 只读配置分组：标题 + 说明 + 行条目。
 * 每个值都带「默认值」来源标注与可选的环境变量覆盖名、人话帮助。
 */
export default function ConfigurationSection({ section }: { section: ConfigSection }) {
  return (
    <section className="config-section">
      <header className="config-section-head">
        <h3>{section.title}</h3>
        {section.description && <p className="config-section-desc">{section.description}</p>}
      </header>
      <div className="config-rows">
        {section.rows.map((row) => (
          <div key={row.label} className="config-row">
            <span className="config-label">{row.label}</span>
            <div className="config-cell">
              <span className="config-value">{row.value}</span>
              <span className="config-source">
                默认值{row.envVar ? ` · 环境变量 ${row.envVar}` : ''}
              </span>
              {row.hint && <span className="config-hint">{row.hint}</span>}
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
