import { describe, expect, it } from 'vitest'
import { latestEscalation } from './escalating'

const entry = (id: number, eventType: string, details: string, recordedAt: string) => ({
  id,
  eventType,
  actor: null,
  details,
  recordedAt,
  correctsEntryId: null,
})
const at = (iso: string) => Date.parse(iso)

describe('latestEscalation', () => {
  it('names the latest page recorded on the timeline, whatever the policy says now', () => {
    const timeline = [
      entry(1, 'CREATED', 'Incident declared', '2026-10-28T06:00:00Z'),
      entry(3, 'ESCALATED', 'Escalated to level 2: Rohan', '2026-10-28T06:20:00Z'),
      entry(2, 'ESCALATED', 'Escalated to level 1: Asha', '2026-10-28T06:05:00Z'),
    ]

    expect(latestEscalation(timeline, at('2026-10-28T06:27:30Z'))).toBe('Escalated to level 2: Rohan, 7 min ago.')
  })

  it('says just now within the first minute, and names the fallback page as recorded', () => {
    const timeline = [entry(4, 'ESCALATED', 'Escalated to fallback contact: Dev Admin (all levels exhausted)', '2026-10-28T06:40:00Z')]

    expect(latestEscalation(timeline, at('2026-10-28T06:40:30Z'))).toBe(
      'Escalated to fallback contact: Dev Admin (all levels exhausted), just now.',
    )
  })

  it('says so when no level has been paged yet', () => {
    expect(latestEscalation([entry(1, 'CREATED', 'Incident declared', '2026-10-28T06:00:00Z')], at('2026-10-28T06:02:00Z'))).toBe(
      'No escalation level has been paged yet.',
    )
  })
})
