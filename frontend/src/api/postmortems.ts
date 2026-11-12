import { z } from 'zod'
import { apiFetch } from './client'

const personSchema = z.object({
  id: z.number(),
  displayName: z.string(),
})

export const postmortemSchema = z.object({
  id: z.number(),
  incidentId: z.number(),
  status: z.enum(['DRAFT', 'REVIEWED']),
  summary: z.string(),
  impact: z.string(),
  rootCause: z.string(),
  resolution: z.string(),
  wentWell: z.string(),
  wentBadly: z.string(),
  author: personSchema,
  reviewedBy: personSchema.nullable().optional(),
  reviewedAt: z.string().nullable().optional(),
  updatedAt: z.string(),
  version: z.number(),
})

export type Postmortem = z.infer<typeof postmortemSchema>

export const postmortemInputSchema = z.object({
  summary: z.string().max(10000),
  impact: z.string().max(10000),
  rootCause: z.string().max(10000),
  resolution: z.string().max(10000),
  wentWell: z.string().max(10000),
  wentBadly: z.string().max(10000),
  expectedVersion: z.number().nullable(),
})

export type PostmortemInput = z.infer<typeof postmortemInputSchema>

export function getPostmortem(incidentId: number) {
  return apiFetch(`/incidents/${incidentId}/postmortem`, postmortemSchema)
}

export function savePostmortem(incidentId: number, body: PostmortemInput) {
  return apiFetch(`/incidents/${incidentId}/postmortem`, postmortemSchema, {
    method: 'PUT',
    body,
  })
}

export function reviewPostmortem(incidentId: number) {
  return apiFetch(`/incidents/${incidentId}/postmortem/review`, postmortemSchema, {
    method: 'POST',
  })
}

export const followUpSchema = z.object({
  id: z.number(),
  incidentId: z.number(),
  incidentReference: z.string(),
  title: z.string(),
  kind: z.enum(['CORRECTIVE', 'PREVENTIVE']),
  owner: personSchema,
  dueDate: z.string(),
  status: z.enum(['OPEN', 'IN_PROGRESS', 'DONE']),
  overdue: z.boolean(),
  completedAt: z.string().nullable(),
  createdAt: z.string(),
})

export type FollowUp = z.infer<typeof followUpSchema>

export function getFollowUps(incidentId: number) {
  return apiFetch(`/incidents/${incidentId}/follow-ups`, z.array(followUpSchema))
}

export function createFollowUp(
  incidentId: number,
  body: { title: string; kind: 'CORRECTIVE' | 'PREVENTIVE'; ownerId: number; dueDate: string },
) {
  return apiFetch(`/incidents/${incidentId}/follow-ups`, followUpSchema, {
    method: 'POST',
    body,
  })
}

export function updateFollowUp(
  id: number,
  body: {
    title: string
    kind: 'CORRECTIVE' | 'PREVENTIVE'
    ownerId: number
    dueDate: string
    status: 'OPEN' | 'IN_PROGRESS' | 'DONE'
  },
) {
  return apiFetch(`/follow-ups/${id}`, followUpSchema, {
    method: 'PUT',
    body,
  })
}
