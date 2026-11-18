import fc from 'fast-check'
import { describe, expect, it } from 'vitest'
import type { OnCallSchedule, Shift } from '@/api/onCall'
import { coverage, onCallAt } from './schedule'

// Property-based checks of the coverage bar and who is on call (D12): fast-check generates random non-overlapping
// shifts and windows, 200 cases per property, and shrinks a failure to the smallest one. schedule.test.ts keeps the
// hand-picked cases.

const BASE = Date.parse('2026-11-16T18:30:00Z') // 00:00 IST on 17 Nov
const MINUTE = 60_000
const SPAN = 3 * 24 * 60 // three days, in minutes

/** Shifts that never overlap (the server refuses overlaps): sorted distinct minute marks, paired into [start, end). */
const shiftsArb = fc
  .uniqueArray(fc.integer({ min: 0, max: SPAN }), { minLength: 0, maxLength: 12 })
  .chain((marks) => {
    const sorted = marks.toSorted((a, b) => a - b)
    const pairs: [number, number][] = []
    for (let i = 0; i + 1 < sorted.length; i += 2) pairs.push([sorted[i], sorted[i + 1]])
    return fc.subarray(pairs)
  })
  .map((pairs) =>
    pairs.map(
      ([start, end], index): Shift => ({
        id: index + 1,
        responderId: index + 1,
        responderName: `Responder ${index + 1}`,
        startTime: new Date(BASE + start * MINUTE).toISOString(),
        endTime: new Date(BASE + end * MINUTE).toISOString(),
      }),
    ),
  )

/** A window [from, to) somewhere in or around the shifts, at least a minute long. */
const windowArb = fc
  .tuple(fc.integer({ min: -60, max: SPAN + 60 }), fc.integer({ min: 1, max: 24 * 60 }))
  .map(([start, length]) => ({ from: BASE + start * MINUTE, to: BASE + (start + length) * MINUTE }))

const schedule = (shifts: Shift[]): OnCallSchedule => ({
  serviceId: 1,
  fallbackContactId: 9,
  fallbackContactName: 'Fallback',
  shifts,
})

describe('coverage (property-based)', () => {
  it('tiles the window exactly: no hole, no overlap, never two gaps in a row', () => {
    fc.assert(
      fc.property(shiftsArb, windowArb, (shifts, { from, to }) => {
        const parts = coverage(shifts, from, to)
        expect(parts.length).toBeGreaterThan(0)
        expect(parts[0].start).toBe(from)
        expect(parts.at(-1)!.end).toBe(to)
        parts.forEach((part, index) => {
          expect(part.start).toBeLessThan(part.end)
          if (index > 0) {
            expect(part.start).toBe(parts[index - 1].end)
            expect(part.kind === 'gap' && parts[index - 1].kind === 'gap').toBe(false)
          }
          if (part.kind === 'shift') {
            expect(part.start).toBeGreaterThanOrEqual(Date.parse(part.shift.startTime))
            expect(part.end).toBeLessThanOrEqual(Date.parse(part.shift.endTime))
          }
        })
      }),
      { numRuns: 200 },
    )
  })

  it('agrees with who gets a new incident at any moment of the window', () => {
    fc.assert(
      fc.property(shiftsArb, windowArb, fc.double({ min: 0, max: 1, noNaN: true, maxExcluded: true }), (shifts, { from, to }, fraction) => {
        // Floor the offset before adding it: near 1.8e12, from + 0.99999… × span can round up to `to` itself.
        const at = from + Math.floor(fraction * (to - from))
        const part = coverage(shifts, from, to).find((candidate) => candidate.start <= at && at < candidate.end)!
        const onCall = onCallAt(schedule(shifts), at)
        if (part.kind === 'shift') {
          expect(onCall).toEqual({ kind: 'shift', name: part.shift.responderName, until: part.shift.endTime })
        } else {
          expect(onCall).toEqual({ kind: 'fallback', name: 'Fallback' })
        }
      }),
      { numRuns: 200 },
    )
  })
})
