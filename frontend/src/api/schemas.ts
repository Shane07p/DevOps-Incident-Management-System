import { z } from 'zod'

export const roleSchema = z.enum(['ADMIN', 'ENGINEER', 'VIEWER'])

export type Role = z.infer<typeof roleSchema>

export const severitySchema = z.enum(['SEV1', 'SEV2', 'SEV3', 'SEV4'])

export type Severity = z.infer<typeof severitySchema>

export const criticalitySchema = z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])

export type Criticality = z.infer<typeof criticalitySchema>

export const userSchema = z.object({
  id: z.number(),
  email: z.string(),
  displayName: z.string(),
  role: roleSchema,
})

export type User = z.infer<typeof userSchema>

/** A person in the Admin's member list: as a user, plus whether they may sign in to this org. */
export const memberSchema = userSchema.extend({ enabled: z.boolean() })

export type Member = z.infer<typeof memberSchema>

// Everyone works inside one org; another org's data never reaches them (D48).
export const orgSchema = z.object({ id: z.number(), name: z.string(), slug: z.string() })

export type Org = z.infer<typeof orgSchema>

export const loginResponseSchema = z.object({
  token: z.string(),
  expiresAt: z.iso.datetime({ offset: true }),
  user: userSchema,
  org: orgSchema,
})

export type LoginResponse = z.infer<typeof loginResponseSchema>

export const meResponseSchema = z.object({ user: userSchema, org: orgSchema })

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
