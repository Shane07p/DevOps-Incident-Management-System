import type { IncidentStatus } from '@/api/schemas'
import { formatMinutes } from './time'

/**
 * Where one SLA clock stands (FR-16). Both clocks start at creation; the response clock stops at the first
 * acknowledgement and the resolution clock at RESOLVED. Going past the due time is a breach; reaching it
 * exactly is not (AGENTS.md), which is why every comparison here is a strict `>`.
 */
export type SlaState =
  | { kind: 'running'; msLeft: number }
  | { kind: 'overdue'; msOver: number }
  | { kind: 'met' }
  | { kind: 'breached'; msOver: number }

/** `now` comes in, rather than being read here, so the page can tick and tests can hold the clock still. */
export function slaState(dueAt: string, stoppedAt: string | null, now: number): SlaState {
  const due = Date.parse(dueAt)
  if (stoppedAt !== null) {
    const stopped = Date.parse(stoppedAt)
    return stopped > due ? { kind: 'breached', msOver: stopped - due } : { kind: 'met' }
  }
  return now > due ? { kind: 'overdue', msOver: now - due } : { kind: 'running', msLeft: due - now }
}

export const isBreached = (state: SlaState) => state.kind === 'overdue' || state.kind === 'breached'

/** "12 min left", "Breached · 5 min overdue", "Met", or "Breached". */
export function slaHeadline(state: SlaState) {
  switch (state.kind) {
    case 'running':
      return `${formatDuration(state.msLeft)} left`
    case 'overdue':
      return `Breached · ${formatDuration(state.msOver)} overdue`
    case 'met':
      return 'Met'
    case 'breached':
      return 'Breached'
  }
}

/** 45 000 → "45 s", 3 900 000 → "1 h 5 min". The last minute counts down in seconds. */
export function formatDuration(ms: number) {
  const seconds = Math.floor(ms / 1000)
  return seconds < 60 ? `${seconds} s` : formatMinutes(Math.floor(seconds / 60))
}

export type ClockName = 'Response' | 'Resolution'

/**
 * The clock that still runs on an incident in the list, which has no stop times: the response clock until
 * the incident leaves OPEN (only an acknowledgement does that), then the resolution clock until RESOLVED.
 * Null once both have stopped.
 */
export function runningClock(incident: { status: IncidentStatus; responseDueAt: string; resolutionDueAt: string }) {
  if (incident.status === 'OPEN') return { name: 'Response' as ClockName, dueAt: incident.responseDueAt }
  if (incident.status === 'RESOLVED' || incident.status === 'CLOSED') return null
  return { name: 'Resolution' as ClockName, dueAt: incident.resolutionDueAt }
}
