import { z } from 'zod'
import { apiFetch } from './client'

export const SEVERITIES = ['SEV1', 'SEV2', 'SEV3', 'SEV4'] as const
export type Severity = (typeof SEVERITIES)[number]

export const CRITICALITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const
export type Criticality = (typeof CRITICALITIES)[number]

const severitySchema = z.enum(SEVERITIES)

const slaTargetSchema = z.object({
  responseMinutes: z.number().int(),
  resolutionMinutes: z.number().int(),
})

export const serviceSchema = z.object({
  id: z.number(),
  name: z.string(),
  description: z.string().nullable(),
  owner: z.object({ id: z.number(), displayName: z.string() }),
  criticality: z.enum(CRITICALITIES),
  targets: z.record(severitySchema, slaTargetSchema),
  createdAt: z.string(),
  updatedAt: z.string(),
})
export type Service = z.infer<typeof serviceSchema>

const defaultTargetsSchema = z.record(
  severitySchema,
  z.object({ responseMinutes: z.number().int(), resolutionMinutes: z.number().int().nullable() }),
)
export type DefaultTargets = z.infer<typeof defaultTargetsSchema>

const userSchema = z.object({
  id: z.number(),
  username: z.string(),
  displayName: z.string(),
  role: z.enum(['ADMIN', 'ENGINEER', 'VIEWER']),
})
export type User = z.infer<typeof userSchema>

export type ServiceRequest = {
  name: string
  description: string | null
  ownerId: number
  criticality: Criticality
  targets: Record<Severity, { responseMinutes: number; resolutionMinutes: number }>
}

export const listServices = () => apiFetch('/services', z.array(serviceSchema))
export const getService = (id: number) => apiFetch(`/services/${id}`, serviceSchema)
export const getDefaultTargets = () => apiFetch('/services/default-targets', defaultTargetsSchema)
export const createService = (body: ServiceRequest) =>
  apiFetch('/services', serviceSchema, { method: 'POST', body })
export const updateService = (id: number, body: ServiceRequest) =>
  apiFetch(`/services/${id}`, serviceSchema, { method: 'PUT', body })
/** Owner choices. Admin-only endpoint from US-01, matching the Sprint 1 contract. */
export const listUsers = () => apiFetch('/users', z.array(userSchema))
