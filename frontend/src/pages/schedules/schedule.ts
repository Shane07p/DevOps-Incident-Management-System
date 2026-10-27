import type { OnCallSchedule, Shift, ShiftRequest } from '@/api/onCall'

// Shifts start inclusive and end exclusive (D12), the same rule the server uses to assign incidents.
const covers = (shift: Shift, at: number) => Date.parse(shift.startTime) <= at && at < Date.parse(shift.endTime)

export type OnCallNow =
  | { kind: 'shift'; username: string; until: string }
  | { kind: 'fallback'; username: string }
  | { kind: 'nobody' }

/** Who a new incident on this service would go to at `at`: the shift covering it, else the fallback contact. */
export function onCallAt(schedule: OnCallSchedule, at: number): OnCallNow {
  const shift = schedule.shifts.find((candidate) => covers(candidate, at))
  if (shift) return { kind: 'shift', username: shift.responderUsername, until: shift.endTime }
  if (schedule.fallbackContactUsername) return { kind: 'fallback', username: schedule.fallbackContactUsername }
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
