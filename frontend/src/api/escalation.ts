import { z } from 'zod'
import { ApiRequestError, apiFetch } from './client'

export const escalationLevelSchema = z.object({
  id: z.number(),
  stepNumber: z.number(),
  responderId: z.number(),
  responderDisplayName: z.string(),
  waitMinutes: z.number(),
})

export type EscalationLevel = z.infer<typeof escalationLevelSchema>

export const escalationPolicySchema = z.object({
  id: z.number(),
  serviceId: z.number(),
  steps: z.array(escalationLevelSchema),
  updatedAt: z.iso.datetime({ offset: true }),
})

export type EscalationPolicy = z.infer<typeof escalationPolicySchema>

/** One level of PUT /api/services/{id}/escalation-policy; levels are numbered from 1 in the order they are paged. */
export interface EscalationLevelRequest {
  stepNumber: number
  responderId: number
  waitMinutes: number
}

/** Every role may read a policy. A service without one answers 404, which here means "no levels yet". */
export async function getEscalationPolicy(serviceId: number) {
  try {
    return await apiFetch(`/services/${serviceId}/escalation-policy`, escalationPolicySchema)
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 404) return null
    throw error
  }
}

/** Admins only. It replaces every level, so send the whole list. */
export function updateEscalationPolicy(serviceId: number, steps: EscalationLevelRequest[]) {
  return apiFetch(`/services/${serviceId}/escalation-policy`, escalationPolicySchema, { method: 'PUT', body: { steps } })
}
