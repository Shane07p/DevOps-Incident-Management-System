import type { PublicHealth, PublicStatus } from '@/api/publicStatus'

export const PUBLIC_HEALTH = {
  OPERATIONAL: { tone: 'ok', label: 'Operational' },
  DEGRADED: { tone: 'warn', label: 'Degraded' },
  DOWN: { tone: 'fail', label: 'Major outage' },
  UNKNOWN: { tone: 'neutral', label: 'No data yet' },
} as const satisfies Record<PublicHealth, { tone: 'ok' | 'warn' | 'fail' | 'neutral'; label: string }>

/** The public page and its management screen describe the same customer-visible state. */
export function publicStatusSummary(data: PublicStatus) {
  const affected = new Set(data.activeIncidents.map((incident) => incident.serviceName))
  for (const service of data.services) {
    if (service.health === 'DOWN' || service.health === 'DEGRADED') affected.add(service.name)
  }
  const names = [...affected]
  const text = names.length === 0 ? `${data.orgName} service status` : `${names.join(', ')} ${names.length === 1 ? 'is' : 'are'} affected.`
  if (data.services.some((service) => service.health === 'DOWN')) {
    return { title: 'Major outage', text, tone: 'fail' as const }
  }
  if (names.length > 0) {
    return { title: 'Partial outage', text, tone: 'warn' as const }
  }
  return { title: 'All systems operational', text, tone: 'ok' as const }
}
