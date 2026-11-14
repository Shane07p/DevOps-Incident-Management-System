import type { IncidentStatus } from '@/api/schemas'

/**
 * The status changes a responder can choose, from AGENTS.md: the normal path, plus INVESTIGATING → MONITORING
 * and MONITORING → INVESTIGATING. OPEN → ACKNOWLEDGED is not here: it happens through Acknowledge, which also
 * makes the acknowledger the assignee. The server rejects anything else whatever the menu shows.
 */
const NEXT: Record<IncidentStatus, IncidentStatus[]> = {
  OPEN: [],
  ACKNOWLEDGED: ['INVESTIGATING'],
  INVESTIGATING: ['IDENTIFIED', 'MONITORING'],
  IDENTIFIED: ['MONITORING'],
  MONITORING: ['RESOLVED', 'INVESTIGATING'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
}

export function nextStatuses(status: IncidentStatus) {
  return NEXT[status]
}

/** RESOLVED needs a recovery note (architecture §8); other changes may add one. */
export const needsNote = (status: IncidentStatus) => status === 'RESOLVED'
