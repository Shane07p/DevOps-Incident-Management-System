import { z } from 'zod'

export const roleSchema = z.enum(['ADMIN', 'ENGINEER', 'VIEWER'])

export type Role = z.infer<typeof roleSchema>

export const severitySchema = z.enum(['SEV1', 'SEV2', 'SEV3', 'SEV4'])

export type Severity = z.infer<typeof severitySchema>

export const criticalitySchema = z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])

export type Criticality = z.infer<typeof criticalitySchema>

export const userSchema = z.object({
  id: z.number(),
  username: z.string(),
  displayName: z.string(),
  role: roleSchema,
})

export type User = z.infer<typeof userSchema>

export const loginResponseSchema = z.object({
  token: z.string(),
  expiresAt: z.iso.datetime({ offset: true }),
  user: userSchema,
})

export const noContentSchema = z.undefined()

export const incidentStatusSchema = z.enum([
  'OPEN',
  'ACKNOWLEDGED',
  'INVESTIGATING',
  'IDENTIFIED',
  'MONITORING',
  'RESOLVED',
  'CLOSED',
])

export type IncidentStatus = z.infer<typeof incidentStatusSchema>
