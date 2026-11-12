import { z } from 'zod'
import { apiFetch } from './client'
import { noContentSchema } from './schemas'

const timestamp = z.iso.datetime({ offset: true })

/**
 * The org's own Grafana connection (D49). The token never comes back: `tokenSaved` only says one is there.
 * With no connection, `connected` is false and the naming fields hold the server's defaults.
 */
export const telemetryConnectionSchema = z.object({
  connected: z.boolean(),
  prometheusUrl: z.string().nullable(),
  prometheusUser: z.string().nullable(),
  lokiUrl: z.string().nullable(),
  lokiUser: z.string().nullable(),
  tokenSaved: z.boolean(),
  serviceLabel: z.string(),
  requestsMetric: z.string(),
  statusLabel: z.string(),
  durationMetric: z.string(),
  updatedAt: timestamp.nullable(),
  updatedBy: z.string().nullable(),
})

export type TelemetryConnection = z.infer<typeof telemetryConnectionSchema>

/** Per store: "ok", "not connected", or why the call failed. */
export const telemetryTestSchema = z.object({ prometheus: z.string(), loki: z.string() })

export type TelemetryTest = z.infer<typeof telemetryTestSchema>

/** The body of PUT. An empty token keeps the saved one; empty naming fields take the defaults. */
export interface TelemetryConnectionRequest {
  prometheusUrl: string
  prometheusUser: string
  lokiUrl: string
  lokiUser: string
  token: string
  serviceLabel: string
  requestsMetric: string
  statusLabel: string
  durationMetric: string
}

/** Admins only, like every call here. */
export function getTelemetryConnection() {
  return apiFetch('/settings/telemetry', telemetryConnectionSchema)
}

export function saveTelemetryConnection(request: TelemetryConnectionRequest) {
  return apiFetch('/settings/telemetry', telemetryConnectionSchema, { method: 'PUT', body: request })
}

export function deleteTelemetryConnection() {
  return apiFetch('/settings/telemetry', noContentSchema, { method: 'DELETE' })
}

export function testTelemetryConnection() {
  return apiFetch('/settings/telemetry/test', telemetryTestSchema, { method: 'POST' })
}
