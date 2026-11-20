import { z } from 'zod'
import { apiFetch } from './client'

const timestamp = z.iso.datetime({ offset: true })

export const investigationSchema = z.object({
  id: z.number(),
  summary: z.string(),
  hypotheses: z.array(z.object({
    rank: z.number(),
    title: z.string(),
    confidence: z.number(),
    evidence: z.array(z.object({
      kind: z.string(),
      text: z.string(),
    })),
  })),
  suggestedActions: z.array(z.object({
    id: z.number(),
    text: z.string(),
    decision: z.enum(['ACCEPTED', 'REJECTED']).nullable(),
    decidedBy: z.string().nullable(),
    decidedAt: timestamp.nullable(),
  })),
  createdAt: timestamp,
})

export type Investigation = z.infer<typeof investigationSchema>

export const draftKindSchema = z.enum(['SUMMARY', 'STATUS_UPDATE', 'POSTMORTEM'])
export type DraftKind = z.infer<typeof draftKindSchema>

export const incidentDraftSchema = z.object({
  kind: draftKindSchema,
  text: z.string(),
  postmortem: z.object({
    summary: z.string(),
    impact: z.string(),
    rootCause: z.string(),
    resolution: z.string(),
    wentWell: z.string(),
    wentBadly: z.string(),
  }).nullable(),
  generatedAt: timestamp,
})

export type IncidentDraft = z.infer<typeof incidentDraftSchema>

export function getLatestInvestigation(incidentId: number) {
  return apiFetch(
    `/incidents/${incidentId}/investigations/latest`,
    investigationSchema,
  )
}

export function runInvestigation(incidentId: number) {
  return apiFetch(
    `/incidents/${incidentId}/investigations`,
    investigationSchema,
    { method: 'POST' },
  )
}

export function decideSuggestedAction(
  investigationId: number,
  actionId: number,
  decision: 'ACCEPTED' | 'REJECTED',
) {
  return apiFetch(
    `/investigations/${investigationId}/actions/${actionId}/decision`,
    z.unknown(),
    { method: 'POST', body: { decision } },
  )
}

export function generateIncidentDraft(incidentId: number, kind: DraftKind) {
  return apiFetch(
    `/incidents/${incidentId}/drafts`,
    incidentDraftSchema,
    { method: 'POST', body: { kind } },
  )
}
