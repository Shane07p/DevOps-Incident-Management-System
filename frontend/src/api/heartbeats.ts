import { z } from 'zod'
import { apiFetch } from './client'

const timestamp = z.iso.datetime({ offset: true })

/** A scheduled job that must check in (FR-24). The ping token is left out: this page never shows it. */
export const heartbeatSchema = z.object({
  id: z.number(),
  name: z.string(),
  serviceId: z.number(),
  serviceName: z.string(),
  intervalSeconds: z.number(),
  graceSeconds: z.number(),
  enabled: z.boolean(),
  // Null until the job's first ping.
  lastPingAt: timestamp.nullable(),
  nextDueAt: timestamp.nullable(),
  // Past this the job is missed and an alert is raised.
  lateAfter: timestamp.nullable(),
  outageStartedAt: timestamp.nullable(),
  activeAlertId: z.number().nullable(),
})

export type Heartbeat = z.infer<typeof heartbeatSchema>

/** Every role may read the list. */
export function listHeartbeats() {
  return apiFetch('/heartbeats', z.array(heartbeatSchema))
}
