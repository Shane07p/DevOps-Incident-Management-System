import { z } from 'zod'
import { apiFetch } from './client'
import { criticalitySchema } from './schemas'

/** A service's health from its monitors (#180): the worst monitor wins; no checked monitor is UNKNOWN. */
export const healthSchema = z.enum(['UNKNOWN', 'OPERATIONAL', 'DEGRADED', 'DOWN'])

export type Health = z.infer<typeof healthSchema>

export const serviceMapSchema = z.object({
  nodes: z.array(z.object({ id: z.number(), name: z.string(), criticality: criticalitySchema, health: healthSchema })),
  // `from` depends on `to`.
  edges: z.array(z.object({ from: z.number(), to: z.number() })),
})

export type ServiceMap = z.infer<typeof serviceMapSchema>

/** Every role may read the map. */
export function getServiceMap() {
  return apiFetch('/service-map', serviceMapSchema)
}

/** The services that depend on `serviceId`, directly or through others: what may break if it fails. */
export function getBlastRadius(serviceId: number) {
  return apiFetch(`/services/${serviceId}/blast-radius`, z.array(z.object({ id: z.number(), name: z.string() })))
}

/** Admins only. Replaces the services `serviceId` depends on. */
export function updateDependencies(serviceId: number, dependsOn: number[]) {
  return apiFetch(
    `/services/${serviceId}/dependencies`,
    z.object({ serviceId: z.number(), dependsOn: z.array(z.number()) }),
    { method: 'PUT', body: { dependsOn } },
  )
}
