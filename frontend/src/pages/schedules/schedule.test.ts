import { describe, expect, it } from 'vitest'
import type { OnCallSchedule } from '@/api/onCall'
import { coverage, findOverlap, nextShift, onCallAt } from './schedule'

// Engineer 09:00 to 17:00 IST, then admin 17:00 to 21:00 IST, on 28 Oct; the fallback is admin.
const schedule: OnCallSchedule = {
  serviceId: 1,
  fallbackContactId: 1,
  fallbackContactName: 'admin',
  shifts: [
    { id: 11, responderId: 2, responderName: 'engineer', startTime: '2026-10-28T03:30:00Z', endTime: '2026-10-28T11:30:00Z' },
    { id: 12, responderId: 1, responderName: 'admin', startTime: '2026-10-28T11:30:00Z', endTime: '2026-10-28T15:30:00Z' },
  ],
}

const at = (iso: string) => Date.parse(iso)

// TC-FR-15.2e: the page shows the same person the server would assign, at each boundary.
describe('onCallAt', () => {
  it('counts the first moment of a shift as inside it', () => {
    expect(onCallAt(schedule, at('2026-10-28T03:30:00Z'))).toEqual({ kind: 'shift', name: 'engineer', until: '2026-10-28T11:30:00Z' })
  })

  it('counts one second before the end as inside it', () => {
    expect(onCallAt(schedule, at('2026-10-28T11:29:59Z'))).toMatchObject({ kind: 'shift', name: 'engineer' })
  })

  it('hands over at the exact end time', () => {
    expect(onCallAt(schedule, at('2026-10-28T11:30:00Z'))).toMatchObject({ kind: 'shift', name: 'admin' })
  })

  it('falls back to the fallback contact in a gap', () => {
    expect(onCallAt(schedule, at('2026-10-28T16:00:00Z'))).toEqual({ kind: 'fallback', name: 'admin' })
  })

  it('says nobody when there is no shift and no fallback contact', () => {
    const empty = { ...schedule, fallbackContactId: null, fallbackContactName: null }
    expect(onCallAt(empty, at('2026-10-28T16:00:00Z'))).toEqual({ kind: 'nobody' })
  })
})

describe('nextShift', () => {
  it('is the first shift that starts later', () => {
    expect(nextShift(schedule, at('2026-10-28T06:00:00Z'))?.id).toBe(12)
    expect(nextShift(schedule, at('2026-10-28T12:00:00Z'))).toBeUndefined()
  })
})

describe('findOverlap', () => {
  it('finds a shift that shares any time', () => {
    expect(findOverlap(schedule.shifts, at('2026-10-28T10:00:00Z'), at('2026-10-28T12:00:00Z'))?.id).toBe(11)
  })

  it('allows a shift that starts exactly when another ends', () => {
    expect(findOverlap(schedule.shifts, at('2026-10-28T15:30:00Z'), at('2026-10-28T20:00:00Z'))).toBeUndefined()
  })
})

describe('coverage', () => {
  // 28 Oct in IST: midnight is 27 Oct 18:30 UTC.
  const dayStart = at('2026-10-27T18:30:00Z')
  const dayEnd = at('2026-10-28T18:30:00Z')

  it('splits the day into the shifts and the gaps around them', () => {
    const parts = coverage(schedule.shifts, dayStart, dayEnd)

    expect(parts.map((part) => [part.kind, part.start, part.end])).toEqual([
      ['gap', dayStart, at('2026-10-28T03:30:00Z')],
      ['shift', at('2026-10-28T03:30:00Z'), at('2026-10-28T11:30:00Z')],
      ['shift', at('2026-10-28T11:30:00Z'), at('2026-10-28T15:30:00Z')],
      ['gap', at('2026-10-28T15:30:00Z'), dayEnd],
    ])
  })

  it('clips a shift that runs past the window, and leaves no gap it covers', () => {
    const overnight = [{ ...schedule.shifts[0], startTime: '2026-10-27T15:30:00Z', endTime: '2026-10-28T20:30:00Z' }]

    expect(coverage(overnight, dayStart, dayEnd)).toEqual([{ kind: 'shift', shift: overnight[0], start: dayStart, end: dayEnd }])
  })

  it('is one gap when no shift touches the window', () => {
    expect(coverage([], dayStart, dayEnd)).toEqual([{ kind: 'gap', start: dayStart, end: dayEnd }])
  })
})
