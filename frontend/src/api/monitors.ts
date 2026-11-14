
import { z } from 'zod'
import { apiFetch } from './client'

const timestamp = z.iso.datetime({ offset: true })

export const monitorSchema = z.object({
  id: z.number(),
  name: z.string(),
  url: z.string(),
  expectedStatus: z.number(),
  intervalSeconds: z.number(),
  serviceId: z.number(),
  serviceName: z.string(),
  failureThreshold: z.number(),
  enabled: z.boolean(),
  consecutiveFailures: z.number(),
  outageStartedAt: timestamp.nullable(),
  activeAlertId: z.number().nullable(),
  // Null until the first check has run.
  lastCheckedAt: timestamp.nullable(),
  nextCheckAt: timestamp.nullable(),
  createdAt: timestamp,
  updatedAt: timestamp,
})

export type Monitor = z.infer<typeof monitorSchema>

export interface MonitorRequest {
  name: string
  url: string
  expectedStatus: number
  intervalSeconds: number
  serviceId: number
  failureThreshold: number
  enabled: boolean
}

export function listMonitors() {
  return apiFetch('/monitors', z.array(monitorSchema))
}

export function createMonitor(monitor: MonitorRequest) {
  return apiFetch('/monitors', monitorSchema, {
    method: 'POST',
    body: monitor,
  })
}

export function updateMonitor(id: number, monitor: MonitorRequest) {
  return apiFetch(`/monitors/${id}`, monitorSchema, {
    method: 'PUT',
    body: monitor,
  })
}
