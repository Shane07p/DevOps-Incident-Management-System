import type { OnCallSchedule, Shift, ShiftRequest } from '@/api/onCall'

// Shifts start inclusive and end exclusive (D12), the same rule the server uses to assign incidents.
const covers = (shift: Shift, at: number) => Date.parse(shift.startTime) <= at && at < Date.parse(shift.endTime)

export type OnCallNow =
  | { kind: 'shift'; name: string; until: string }
  | { kind: 'fallback'; name: string }
  | { kind: 'nobody' }

/** Who a new incident on this service would go to at `at`: the shift covering it, else the fallback contact. */
export function onCallAt(schedule: OnCallSchedule, at: number): OnCallNow {
  const shift = schedule.shifts.find((candidate) => covers(candidate, at))
  if (shift) return { kind: 'shift', name: shift.responderName, until: shift.endTime }
  if (schedule.fallbackContactName) return { kind: 'fallback', name: schedule.fallbackContactName }
  return { kind: 'nobody' }
}

/** The first shift that starts after `at`. */
export function nextShift(schedule: OnCallSchedule, at: number) {
  return schedule.shifts
    .filter((shift) => Date.parse(shift.startTime) > at)
    .toSorted((a, b) => Date.parse(a.startTime) - Date.parse(b.startTime))[0]
}

/** An existing shift that shares any time with [start, end); touching end to start is allowed. */
export function findOverlap(shifts: Shift[], start: number, end: number) {
  return shifts.find((shift) => start < Date.parse(shift.endTime) && Date.parse(shift.startTime) < end)
}

export const toShiftRequest = (shift: Shift): ShiftRequest => ({
  responderId: shift.responderId,
  startTime: shift.startTime,
  endTime: shift.endTime,
})

export type Coverage =
  | { kind: 'shift'; shift: Shift; start: number; end: number }
  | { kind: 'gap'; start: number; end: number }

/**
 * The window [from, to) split into the shifts that cover it (clipped to the window) and the gaps between them,
 * in time order. A gap is when a new incident would go to the fallback contact. Shifts never overlap (D12).
 */
export function coverage(shifts: Shift[], from: number, to: number): Coverage[] {
  const parts: Coverage[] = []
  let cursor = from
  const inside = shifts
    .filter((shift) => Date.parse(shift.startTime) < to && Date.parse(shift.endTime) > from)
    .toSorted((a, b) => Date.parse(a.startTime) - Date.parse(b.startTime))
  for (const shift of inside) {
    const start = Math.max(Date.parse(shift.startTime), from)
    const end = Math.min(Date.parse(shift.endTime), to)
    if (start > cursor) parts.push({ kind: 'gap', start: cursor, end: start })
    parts.push({ kind: 'shift', shift, start, end })
    cursor = end
  }
  if (cursor < to) parts.push({ kind: 'gap', start: cursor, end: to })
  return parts
}
