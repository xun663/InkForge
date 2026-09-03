import { useEffect, useState } from 'react'
import { getLlmConfig, updateLlmConfig } from '../../api'
import type { LlmConfigDto } from '../../types'
import Button from '../common/Button'
import LoadingState from '../common/LoadingState'
import ErrorState from '../common/ErrorState'
import { configGuide } from './settingsData'
import ConfigurationSection from './ConfigurationSection'

/**
 * 设置（P4-UI-E + 运行时 LLM 配置）。
 *
 * 顶部「LLM 配置」是运行时区：选择模型、填写 API Key，保存后后端立即生效
 * （Key 仅存内存，重启失效，绝不回显）。其余分组为只读默认配置说明。
 */

/** 便捷模板：切换 provider 时仅当用户未自定义时预填（Ollama 等只作快捷模板，可用性以保存/请求为准）。 */
const PROVIDER_PRESETS: Record<string, { baseUrl: string; model: string }> = {
  mock: { baseUrl: '', model: '' },
  deepseek: { baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' },
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  'openai-compatible': { baseUrl: '', model: '' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'qwen3:14b' },
}

/** 已知默认值集合：当前值属于这些值时视为「未自定义」，可被新 provider 的预设覆盖。 */
const KNOWN_DEFAULT_URLS = new Set([
  'https://api.deepseek.com',
  ...Object.values(PROVIDER_PRESETS).map((p) => p.baseUrl),
])
const KNOWN_DEFAULT_MODELS = new Set([
  'deepseek-chat',
  ...Object.values(PROVIDER_PRESETS).map((p) => p.model),
])

export default function SettingsView() {
  const [advancedOpen, setAdvancedOpen] = useState(false)

  // runtime LLM config
  const [llmConfig, setLlmConfig] = useState<LlmConfigDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const [savedMsg, setSavedMsg] = useState('')
  const [provider, setProvider] = useState('')
  const [model, setModel] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')

  useEffect(() => {
    let cancelled = false
    getLlmConfig()
      .then((cfg) => {
        if (cancelled) return
        setLlmConfig(cfg)
        setProvider(cfg.provider)
        setModel(cfg.model)
        setBaseUrl(cfg.baseUrl)
      })
      .catch(() => {
        if (!cancelled) setLoadError('无法读取当前 LLM 配置')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  /** 便捷预填：当前值为空或属于已知默认值时覆盖为所选 provider 的预设；用户自定义输入保留。 */
  const handleProviderChange = (next: string) => {
    const preset = PROVIDER_PRESETS[next]
    setProvider(next)
    if (!preset) return
    if (!baseUrl.trim() || KNOWN_DEFAULT_URLS.has(baseUrl.trim())) {
      setBaseUrl(preset.baseUrl)
    }
    if (!model.trim() || KNOWN_DEFAULT_MODELS.has(model.trim())) {
      setModel(preset.model)
    }
  }

  const handleSave = async () => {
    setSaving(true)
    setSaveError('')
    setSavedMsg('')
    try {
      const cfg = await updateLlmConfig({
        provider,
        model,
        baseUrl,
        // 空 = 保持不变（不清除已配置的 Key）
        apiKey: apiKey.trim() === '' ? null : apiKey,
      })
      setLlmConfig(cfg)
      setProvider(cfg.provider)
      setModel(cfg.model)
      setBaseUrl(cfg.baseUrl)
      setApiKey('')
      setSavedMsg('配置已生效')
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="settings">
      <header className="settings-head">
        <h2>设置</h2>
        <p className="settings-sub">配置说明 · 运行时 LLM · 默认配置</p>
      </header>

      <p className="settings-guide-note">{configGuide.guideNote}</p>

      {/* 运行时 LLM 配置 */}
      <section className="config-section runtime-llm">
        <header className="config-section-head">
          <h3>LLM 配置</h3>
          <p className="config-section-desc">
            选择续写使用的模型并填写 API Key，保存后立即生效（仅保存在内存，重启后恢复默认）。
          </p>
        </header>

        {loading && <LoadingState label="正在读取当前 LLM 配置……" />}
        {loadError && <ErrorState message={loadError} />}

        {!loading && !loadError && llmConfig && (
          <>
            <div className="runtime-current">
              <span className="runtime-current-label">当前生效</span>
              <span>Provider：{llmConfig.provider}</span>
              <span>Model：{llmConfig.model}</span>
              <span>API Key：{llmConfig.apiKeyConfigured ? '已配置' : '未配置'}</span>
            </div>

            <div className="runtime-form">
              <label className="runtime-field">
                <span>Provider</span>
                <select value={provider} onChange={(e) => handleProviderChange(e.target.value)}>
                  {llmConfig.supportedProviders.map((p) => (
                    <option key={p} value={p}>
                      {p}
                    </option>
                  ))}
                </select>
              </label>
              <label className="runtime-field">
                <span>Model</span>
                <input
                  value={model}
                  onChange={(e) => setModel(e.target.value)}
                  placeholder="deepseek-chat"
                />
              </label>
              <label className="runtime-field">
                <span>Base URL</span>
                <input
                  value={baseUrl}
                  onChange={(e) => setBaseUrl(e.target.value)}
                  placeholder="https://api.deepseek.com"
                />
              </label>
              <label className="runtime-field">
                <span>API Key</span>
                <input
                  type="password"
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="留空保持不变；切换非 mock 时需填写"
                  autoComplete="off"
                />
              </label>
            </div>

            <div className="runtime-actions">
              <Button size="sm" onClick={() => void handleSave()} disabled={saving}>
                {saving ? '保存中……' : '保存配置'}
              </Button>
              {savedMsg && <span className="runtime-ok">{savedMsg}</span>}
              {saveError && <span className="runtime-err">{saveError}</span>}
            </div>

            <p className="runtime-note">
              mock 为内置模拟输出，无需 Key。Key 仅保存在后端内存，后端重启后需重新填写。
              切换 Provider 时会自动填入常用默认值作为便捷提示，可修改；Ollama 等实际可用性以保存与请求结果为准。
            </p>
          </>
        )}
      </section>

      {configGuide.sections.map((section) => (
        <ConfigurationSection key={section.id} section={section} />
      ))}

      {/* API Key 安全说明 */}
      <section className="config-section config-note">
        <h3>{configGuide.apiKeyNote.title}</h3>
        <ul>
          {configGuide.apiKeyNote.lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </section>

      <button className="advanced-toggle" onClick={() => setAdvancedOpen((prev) => !prev)} type="button">
        高级配置 {advancedOpen ? '▾' : '▸'}
      </button>

      {advancedOpen && (
        <div className="advanced-content">
          {configGuide.advanced.map((section) => (
            <ConfigurationSection key={section.id} section={section} />
          ))}
        </div>
      )}
    </div>
  )
}
