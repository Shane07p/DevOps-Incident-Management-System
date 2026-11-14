import type { EscalationLevel } from '@/api/escalation'
import { formatMinutes } from '@/lib/time'

/**
 * Where an unacknowledged incident is in the paging order (D14) at `now`: each level is paged its wait after the
 * one before (the first, after the incident opens), then the fallback contact once, the last wait later. The
 * levels come sorted. Returns one sentence, or null when there are no levels.
 */
export function escalationNow(levels: EscalationLevel[], fallback: string | null, openedAt: string, now: number) {
  if (levels.length === 0) return null
  const pagedAt = levels.reduce<number[]>((times, level) => [...times, (times.at(-1) ?? 0) + level.waitMinutes], [])
  const fallbackAt = pagedAt[pagedAt.length - 1] + levels[levels.length - 1].waitMinutes
  const elapsed = Math.max(0, (now - Date.parse(openedAt)) / 60_000)
  const inMinutes = (at: number) => formatMinutes(Math.max(1, Math.ceil(at - elapsed)))
  const reached = pagedAt.findLastIndex((at) => at <= elapsed)

  if (reached === -1) return `Nobody acknowledged yet. ${levels[0].responderDisplayName} (level 1) is paged in ${inMinutes(pagedAt[0])}.`
  const ago = Math.floor(elapsed - pagedAt[reached])
  const paged = `${levels[reached].responderDisplayName} (level ${reached + 1}) was paged ${ago < 1 ? 'just now' : `${formatMinutes(ago)} ago`}.`
  if (reached + 1 < levels.length)
    return `${paged} ${levels[reached + 1].responderDisplayName} (level ${reached + 2}) is paged in ${inMinutes(pagedAt[reached + 1])}.`
  if (fallback === null) return `${paged} That is the last level, and there is no fallback contact.`
  if (elapsed < fallbackAt) return `${paged} ${fallback}, the fallback, is paged in ${inMinutes(fallbackAt)}.`
  return `Every level and ${fallback}, the fallback, have been paged.`
}
