
import { z } from 'zod'
import { apiFetch } from './client'

export const runbookSchema = z.object({
  id: z.number(),
  title: z.string(),
  steps: z.array(z.string()),
})

export type Runbook = z.infer<typeof runbookSchema>

export interface RunbookRequest {
  title: string
  steps: string[]
}

// Everyone can view a service's runbooks.
export function listRunbooks(serviceId: number) {
  return apiFetch(
    `/services/${serviceId}/runbooks`,
    z.array(runbookSchema),
  )
}

// Engineers and Admins can create runbooks.
export function createRunbook(
  serviceId: number,
  runbook: RunbookRequest,
) {
  return apiFetch(
    `/services/${serviceId}/runbooks`,
    runbookSchema,
    { method: 'POST', body: runbook },
  )
}

// Engineers and Admins can edit runbooks.
export function updateRunbook(
  id: number,
  runbook: RunbookRequest,
) {
  return apiFetch(
    `/runbooks/${id}`,
    runbookSchema,
    { method: 'PUT', body: runbook },
  )
}

// Engineers and Admins can delete runbooks.
export function deleteRunbook(id: number) {
  return apiFetch(
    `/runbooks/${id}`,
    z.unknown(),
    { method: 'DELETE' },
  )
}
