import { useEffect, useRef, useState } from 'react'
import {
  cancelMemoryBuild,
  getMemoryBuild,
  pauseMemoryBuild,
  resumeMemoryBuild,
  retryFailedMemoryBuild,
  startMemoryBuild,
} from '../../api'
import type { MemoryBuildJob, MemoryBuildStatus } from '../../types'
import Button from '../common/Button'

const ACTIVE: MemoryBuildStatus[] = ['PENDING', 'RUNNING']
const STATUS_LABEL: Record<MemoryBuildStatus, string> = {
  PENDING: '排队中',
  RUNNING: '正在构建',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  PARTIAL_FAILED: '部分失败',
  CANCELLED: '已取消',
}

/**
 * P5-A 全量记忆构建面板：进度 + 状态 + 开始/暂停/继续/重试失败/取消。
 * 轮询 GET /memory/build（1.5s），仅在有活跃 Job 时轮询。Build 失败是局部错误，不影响 Memory Center 其他内容。
 */
export default function MemoryBuildPanel({ novelId }: { novelId: string | null }) {
  const [job, setJob] = useState<MemoryBuildJob | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const refresh = async () => {
    if (!novelId) return
    try {
      setJob(await getMemoryBuild(novelId))
      setError('')
    } catch {
      setError('无法读取记忆构建状态')
    }
  }

  useEffect(() => {
    setJob(null)
    setError('')
    void refresh()
    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current)
        pollingRef.current = null
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [novelId])

  // 活跃时轮询
  useEffect(() => {
    if (!novelId || !job || !ACTIVE.includes(job.status)) return
    if (pollingRef.current) return
    pollingRef.current = setInterval(() => {
      void refresh()
    }, 1500)
    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current)
        pollingRef.current = null
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [novelId, job?.status])

  const run = async (fn: () => Promise<MemoryBuildJob>) => {
    setLoading(true)
    setError('')
    try {
      setJob(await fn())
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败')
    } finally {
      setLoading(false)
    }
  }

  if (!novelId) return null

  const pct = job ? Math.round(job.progress * 100) : 0
  const canStart = job === null || !ACTIVE.includes(job.status)
  const running = job != null && job.status === 'RUNNING'
  const paused = job != null && job.status === 'PAUSED'
  const hasFailures = job != null && job.failedChapters > 0 && !ACTIVE.includes(job.status)

  return (
    <section className="memory-build">
      <header className="memory-build-head">
        <h3>全书记忆构建</h3>
        <span className="memory-build-status">{job ? STATUS_LABEL[job.status] : '未开始'}</span>
      </header>

      {error && <p className="memory-build-error">{error}</p>}

      {job && (
        <div className="memory-build-progress">
          <div className="memory-build-bar">
            <div className="memory-build-fill" style={{ width: `${pct}%` }} />
          </div>
          <div className="memory-build-numbers">
            <span>{job.successChapters} 成功</span>
            <span>{job.failedChapters} 失败</span>
            <span>
              {job.currentOrdinal >= 0 ? `第 ${job.currentOrdinal + 1} 章` : '准备中'}
            </span>
            <span>{job.totalChapters} 章 · {pct}%</span>
          </div>
        </div>
      )}

      <div className="memory-build-actions">
        {canStart && (
          <Button size="sm" disabled={loading} onClick={() => void run(() => startMemoryBuild(novelId))}>
            开始构建
          </Button>
        )}
        {running && (
          <Button variant="secondary" size="sm" disabled={loading} onClick={() => job && void run(() => pauseMemoryBuild(novelId, job.jobId))}>
            暂停
          </Button>
        )}
        {paused && (
          <Button variant="secondary" size="sm" disabled={loading} onClick={() => job && void run(() => resumeMemoryBuild(novelId, job.jobId))}>
            继续
          </Button>
        )}
        {running && (
          <Button variant="danger" size="sm" disabled={loading} onClick={() => job && void run(() => cancelMemoryBuild(novelId, job.jobId))}>
            取消
          </Button>
        )}
        {hasFailures && (
          <Button variant="secondary" size="sm" disabled={loading} onClick={() => job && void run(() => retryFailedMemoryBuild(novelId, job.jobId))}>
            重试失败章节
          </Button>
        )}
      </div>

      {job && job.failedChapters > 0 && job.failedOrdinals.length > 0 && (
        <p className="memory-build-note">失败章节：{job.failedOrdinals.map((o) => o + 1).join(', ')}</p>
      )}
    </section>
  )
}
