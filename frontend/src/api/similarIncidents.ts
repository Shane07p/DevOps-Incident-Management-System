import { z } from 'zod'
import { apiFetch } from './client'
import { incidentStatusSchema, severitySchema } from './schemas'

// ---------------------------------------------------------------------------
// Schemas — aligned with Issue #159 contract (draft, not yet merged)
// ---------------------------------------------------------------------------

/**
 * Similar incident returned by the backend (AI-1 / Issue #159 / #170).
 * Displays up to 3 similar past incidents with match percentage and how each was fixed.
 *
 * Contract: score is always 0.0–1.0, fix is always `fix` (single field).
 * Shape will be finalised when #159 merges; until then, keep this draft.
 */
export const similarIncidentSchema = z.object({
  id: z.number(),
  reference: z.string(),
  title: z.string(),
  serviceName: z.string().optional(),
  severity: severitySchema.optional(),
  status: incidentStatusSchema.optional(),
  /** Similarity score — always 0.0 to 1.0 */
  score: z.number().min(0).max(1),
  /** How the similar incident was resolved */
  fix: z.string().optional(),
})

export type SimilarIncident = z.infer<typeof similarIncidentSchema>

/** GET /api/incidents/{id}/similar always returns a flat array. */
export const similarIncidentsResponseSchema = z.array(similarIncidentSchema)

/**
 * Open duplicate candidate detected during incident declaration.
 * Returned by GET /api/incidents/{id}/similar with status filter
 * or a dedicated endpoint — exact path TBD in #159.
 */
export const duplicateIncidentSchema = z.object({
  id: z.number(),
  reference: z.string(),
  title: z.string(),
  status: incidentStatusSchema.optional(),
  score: z.number().min(0).max(1).optional(),
})

export type DuplicateIncident = z.infer<typeof duplicateIncidentSchema>

/**
 * Duplicate check response: returns a single candidate or null.
 * Exact endpoint path TBD in #159 — currently drafted as
 * GET /api/incidents/duplicate-check?title=...&serviceId=...
 */
export const duplicateCheckResponseSchema = z.object({
  duplicate: z.boolean(),
  incident: duplicateIncidentSchema.nullable().optional(),
  message: z.string().optional(),
}).transform((val) => (val.duplicate && val.incident ? val.incident : null))

// ---------------------------------------------------------------------------
// API Functions
// ---------------------------------------------------------------------------

/**
 * GET /api/incidents/{id}/similar
 * Returns up to 3 similar past incidents above the similarity threshold.
 *
 * Does NOT catch errors — let TanStack Query record the error state so
 * SimilarIncidentsPanel can hide via isError, and tests / console still see
 * genuine failures (e.g. 401, wrong path, schema mismatch).
 */
export async function fetchSimilarIncidents(incidentId: number): Promise<SimilarIncident[]> {
  return apiFetch(`/incidents/${incidentId}/similar`, similarIncidentsResponseSchema)
}

/**
 * GET /api/incidents/duplicate-check?title=...&serviceId=...
 * Checks for a likely open duplicate when declaring an incident.
 * Non-blocking check only. Errors propagate so the component can decide
 * how to handle them (hide warning without blocking declaration).
 */
export async function checkDuplicateIncident(params: {
  title: string
  serviceId?: string | number
}): Promise<DuplicateIncident | null> {
  const trimmed = params.title.trim()
  if (!trimmed || trimmed.length < 3) return null

  const sp = new URLSearchParams()
  sp.set('title', trimmed)
  if (params.serviceId) {
    sp.set('serviceId', String(params.serviceId))
  }

  return apiFetch(`/incidents/duplicate-check?${sp.toString()}`, duplicateCheckResponseSchema)
}
