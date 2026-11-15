import type { TimelineEntry } from '@/api/incidents'
import { formatMinutes } from '@/lib/time'

/**
 * What escalation has done so far on an unacknowledged incident, from its timeline: the latest ESCALATED entry
 * and how long ago it was recorded. The timeline is the record of who was actually paged. The service's current
 * policy is not: an incident keeps the steps it opened with, and each wait counts from when the previous page
 * actually went out (D14), so the policy and the clock cannot say who has been paged.
 */
export function latestEscalation(timeline: TimelineEntry[], now: number) {
  const latest = timeline
    .filter((entry) => entry.eventType === 'ESCALATED' && entry.details)
    .toSorted((a, b) => Date.parse(b.recordedAt) - Date.parse(a.recordedAt))[0]
  if (!latest) return 'No escalation level has been paged yet.'
  const minutes = Math.floor((now - Date.parse(latest.recordedAt)) / 60_000)
  return `${latest.details}, ${minutes < 1 ? 'just now' : `${formatMinutes(minutes)} ago`}.`
}
