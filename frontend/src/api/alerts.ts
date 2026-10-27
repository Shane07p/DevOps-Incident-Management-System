import { z } from 'zod'
import { apiFetch } from './client'

// The values the database allows (V7__alert_intake.sql).
export const alertStatusSchema = z.enum(['NEW', 'ATTACHED', 'ROUTED', 'DUPLICATE', 'IGNORED'])

export type AlertStatus = z.infer<typeof alertStatusSchema>

export const alertSchema = z.object({
  id: z.number(),
  source: z.string(),
  serviceName: z.string(),
  // Every accepted alert belongs to an incident: an existing one it was grouped into, or a new one.
  incidentId: z.number(),
  status: alertStatusSchema,
  correlationKey: z.string().nullable(),
  createdAt: z.iso.datetime({ offset: true }),
})

export type Alert = z.infer<typeof alertSchema>

/** Every role may read the inbox; the server lists alerts newest first. */
export function listAlerts() {
  return apiFetch('/alerts', z.array(alertSchema))
}

/** The server builds references as INC-{id}, so the inbox can name the incident without fetching it. */
export const incidentReference = (incidentId: number) => `INC-${incidentId}`
