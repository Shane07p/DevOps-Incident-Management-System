import type { Heartbeat } from '@/api/heartbeats'
import type { HealthTone } from '@/components/app/Health'

/** Where a job stands now, from its own fields: on time, late within its grace, or missed past it. */
export function heartbeatStatus(heartbeat: Heartbeat, now: number): { tone: HealthTone; label: string } {
  if (!heartbeat.enabled) return { tone: 'neutral', label: 'Paused' }
  if (heartbeat.outageStartedAt || (heartbeat.lateAfter && now > Date.parse(heartbeat.lateAfter))) {
    return { tone: 'fail', label: 'Missed' }
  }
  if (!heartbeat.lastPingAt) return { tone: 'neutral', label: 'Waiting for the first ping' }
  if (heartbeat.nextDueAt && now > Date.parse(heartbeat.nextDueAt)) return { tone: 'warn', label: 'Late' }
  return { tone: 'ok', label: 'On time' }
}
