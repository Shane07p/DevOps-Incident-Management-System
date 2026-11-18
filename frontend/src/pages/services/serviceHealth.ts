import type { Health as ServiceHealth } from '@/api/serviceMap'
import type { IncidentSummary } from '@/api/incidents'
import type { HealthTone } from '@/components/app/Health'
import { FINISHED } from '@/pages/incidents/filters'

/** A service's health from its monitors (#180), as the Health dot and word. UNKNOWN means no monitor has checked yet. */
export const HEALTH: Record<ServiceHealth, { tone: HealthTone; label: string }> = {
  OPERATIONAL: { tone: 'ok', label: 'Operational' },
  DEGRADED: { tone: 'warn', label: 'Degraded' },
  DOWN: { tone: 'fail', label: 'Down' },
  UNKNOWN: { tone: 'neutral', label: 'No checks yet' },
}

const SEVERITY_RANK = { SEV1: 1, SEV2: 2, SEV3: 3, SEV4: 4 } as const

/** The service's incidents still being worked on, the most severe first, then the oldest. */
export function openIncidentsOf(incidents: IncidentSummary[], serviceName: string) {
  return incidents
    .filter((incident) => incident.serviceName === serviceName && !FINISHED.includes(incident.status))
    .toSorted(
      (a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] || a.createdAt.localeCompare(b.createdAt),
    )
}
