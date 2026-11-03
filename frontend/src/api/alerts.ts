import { z } from 'zod'
import { apiFetch } from './client'

// NEW: the alert opened its incident. ATTACHED: it joined one already active (V8__alert_intake.sql).
export const alertStatusSchema = z.enum(['NEW', 'ATTACHED'])

export type AlertStatus = z.infer<typeof alertStatusSchema>

export const alertSchema = z.object({
  id: z.number(),
  source: z.string(),
  serviceName: z.string(),
  // Every accepted alert belongs to an incident: an existing one it was grouped into, or a new one.
  incidentId: z.number(),
  // The org numbers its incidents from INC-1, so the reference is not the id.
  incidentReference: z.string(),
  status: alertStatusSchema,
  correlationKey: z.string().nullable(),
  createdAt: z.iso.datetime({ offset: true }),
})

export type Alert = z.infer<typeof alertSchema>

/** Every role may read the inbox; the server lists alerts newest first. */
export function listAlerts() {
  return apiFetch('/alerts', z.array(alertSchema))
}
