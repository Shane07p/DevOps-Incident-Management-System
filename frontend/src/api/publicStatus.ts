import { z } from 'zod'
import { apiFetch } from './client'

// Temporary response structure until backend issue #160 is finalized.
export const publicStatusSchema = z.object({
  orgName: z.string(),
  services: z.array(
    z.object({
      id: z.number(),
      name: z.string(),
      health: z.enum(['UNKNOWN', 'OPERATIONAL', 'DEGRADED', 'DOWN']),
      uptimePercent: z.number(),
    }),
  ),
  activeIncidents: z.array(
    z.object({
      id: z.number(),
      title: z.string(),
      updates: z.array(
        z.object({
          status: z.string(),
          message: z.string(),
          createdAt: z.string(),
        }),
      ),
    }),
  ),
  resolvedIncidents: z.array(
    z.object({
      id: z.number(),
      title: z.string(),
      resolvedAt: z.string(),
    }),
  ),
})

export type PublicStatus = z.infer<typeof publicStatusSchema>

// Public endpoint: backend does not require authentication.
export function getPublicStatus(slug: string) {
  return apiFetch(
    `/public/status/${encodeURIComponent(slug)}`,
    publicStatusSchema,
  )
}
