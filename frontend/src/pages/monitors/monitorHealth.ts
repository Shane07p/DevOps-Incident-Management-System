import type { Monitor } from '@/api/monitors'
import type { HealthTone } from '@/components/app/Health'
import { formatIst } from '@/lib/time'

/** What a monitor's own fields say about it; no history is invented. */
export function monitorHealth(monitor: Monitor): { tone: HealthTone; label: string } {
  if (!monitor.enabled) return { tone: 'neutral', label: 'Paused' }
  if (monitor.outageStartedAt) return { tone: 'fail', label: `Down since ${formatIst(monitor.outageStartedAt)}` }
  if (monitor.consecutiveFailures > 0) {
    return { tone: 'warn', label: `Failing · ${monitor.consecutiveFailures} of ${monitor.failureThreshold} checks` }
  }
  if (!monitor.lastCheckedAt) return { tone: 'neutral', label: 'Not checked yet' }
  return { tone: 'ok', label: 'Up' }
}
