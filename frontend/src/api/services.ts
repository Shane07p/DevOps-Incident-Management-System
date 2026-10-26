import { z } from 'zod'
import { apiFetch } from './client'
import { criticalitySchema, severitySchema, type Criticality, type Severity } from './schemas'

export const SEVERITIES = severitySchema.options

/** Response targets a new service starts with (D23). The Admin enters every resolution target. */
export const DEFAULT_RESPONSE_MINUTES: Record<Severity, number> = { SEV1: 15, SEV2: 60, SEV3: 240, SEV4: 1440 }

const slaTargetSchema = z.object({
  responseMinutes: z.number(),
  resolutionMinutes: z.number(),
})

export type SlaTarget = z.infer<typeof slaTargetSchema>

export const serviceSchema = z.object({
  id: z.number(),
  name: z.string(),
  description: z.string().nullable(),
  owner: z.object({ id: z.number(), displayName: z.string() }),
  criticality: criticalitySchema,
  // An enum key makes the record exhaustive, so a service missing a severity fails loudly.
  targets: z.record(severitySchema, slaTargetSchema),
})

export type Service = z.infer<typeof serviceSchema>

/** The body of POST /api/services and PUT /api/services/{id}. */
export interface ServiceRequest {
  name: string
  description: string
  ownerId: number
  criticality: Criticality
  targets: Record<Severity, SlaTarget>
}

/** Every role may read the catalog, sorted by name. */
export function listServices() {
  return apiFetch('/services', z.array(serviceSchema))
}

/** Admins only. */
export function createService(service: ServiceRequest) {
  return apiFetch('/services', serviceSchema, { method: 'POST', body: service })
}

/** Admins only. Incidents already open keep the targets they copied at creation. */
export function updateService(id: number, service: ServiceRequest) {
  return apiFetch(`/services/${id}`, serviceSchema, { method: 'PUT', body: service })
}
