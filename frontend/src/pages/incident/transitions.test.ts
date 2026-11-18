import { describe, expect, it } from 'vitest'
import { incidentStatusSchema, type IncidentStatus } from '@/api/schemas'
import { nextStatuses } from './transitions'

// The 8 legal pairs from AGENTS.md; the status menu offers these and nothing else.
const LEGAL: [IncidentStatus, IncidentStatus][] = [
  ['OPEN', 'ACKNOWLEDGED'],
  ['ACKNOWLEDGED', 'INVESTIGATING'],
  ['INVESTIGATING', 'IDENTIFIED'],
  ['IDENTIFIED', 'MONITORING'],
  ['MONITORING', 'RESOLVED'],
  ['RESOLVED', 'CLOSED'],
  ['INVESTIGATING', 'MONITORING'],
  ['MONITORING', 'INVESTIGATING'],
]

const ALL = incidentStatusSchema.options

describe('nextStatuses', () => {
  // OPEN → ACKNOWLEDGED goes through Acknowledge, not the status menu.
  const menuPairs = LEGAL.filter(([from]) => from !== 'OPEN')

  it.each(ALL.flatMap((from) => ALL.map((to) => [from, to] as const)))('%s → %s is offered only if it is legal', (from, to) => {
    const legal = menuPairs.some(([a, b]) => a === from && b === to)
    expect(nextStatuses(from).includes(to)).toBe(legal)
  })

  it('offers nothing from OPEN or CLOSED', () => {
    expect(nextStatuses('OPEN')).toEqual([])
    expect(nextStatuses('CLOSED')).toEqual([])
  })
})
