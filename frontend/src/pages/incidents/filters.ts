import type { IncidentSummary } from '@/api/incidents'
import type { IncidentStatus, Severity } from '@/api/schemas'

export const FINISHED: IncidentStatus[] = ['RESOLVED', 'CLOSED']

/** Which incidents a scope holds: every one, the ones still open, the caller's as Lead, or finished ones. */
export const SCOPES = [
  { id: 'all', label: 'All' },
  { id: 'active', label: 'Active' },
  { id: 'mine', label: 'Assigned to me' },
  { id: 'history', label: 'History' },
] as const

export type Scope = (typeof SCOPES)[number]['id']

export interface IncidentFilters {
  scope: Scope
  q: string
  service: string
  severity: Severity | ''
  status: IncidentStatus | ''
}

/**
 * The filters live in the URL, so a link, a reload, and a switch between table and board all keep them.
 * Unknown values fall back to "everything".
 */
export function readFilters(params: URLSearchParams): IncidentFilters {
  const scope = SCOPES.find((candidate) => candidate.id === params.get('scope'))?.id ?? 'all'
  return {
    scope,
    q: params.get('q') ?? '',
    service: params.get('service') ?? '',
    severity: (params.get('severity') as Severity | null) ?? '',
    status: (params.get('status') as IncidentStatus | null) ?? '',
  }
}

/** Sets one filter in `params`, removing it when it goes back to "everything". */
export function withFilter(params: URLSearchParams, key: keyof IncidentFilters, value: string) {
  const next = new URLSearchParams(params)
  if (value === '' || (key === 'scope' && value === 'all')) next.delete(key)
  else next.set(key, value)
  return next
}

export function inScope(incident: IncidentSummary, scope: Scope, userId: number | undefined) {
  switch (scope) {
    case 'all':
      return true
    case 'active':
      return !FINISHED.includes(incident.status)
    case 'mine':
      return userId !== undefined && incident.assignee?.id === userId
    case 'history':
      return FINISHED.includes(incident.status)
  }
}

export function applyFilters(incidents: IncidentSummary[], filters: IncidentFilters, userId: number | undefined) {
  const q = filters.q.trim().toLowerCase()
  return incidents.filter(
    (incident) =>
      inScope(incident, filters.scope, userId) &&
      (q === '' || `${incident.reference} ${incident.title} ${incident.serviceName}`.toLowerCase().includes(q)) &&
      (filters.service === '' || incident.serviceName === filters.service) &&
      (filters.severity === '' || incident.severity === filters.severity) &&
      (filters.status === '' || incident.status === filters.status),
  )
}

/** The board's columns, by lifecycle stage. */
export const BOARD_COLUMNS: { id: string; label: string; statuses: IncidentStatus[] }[] = [
  { id: 'needs-response', label: 'Needs response', statuses: ['OPEN'] },
  { id: 'in-progress', label: 'In progress', statuses: ['ACKNOWLEDGED', 'INVESTIGATING', 'IDENTIFIED'] },
  { id: 'monitoring', label: 'Monitoring', statuses: ['MONITORING'] },
  { id: 'completed', label: 'Completed', statuses: FINISHED },
]
