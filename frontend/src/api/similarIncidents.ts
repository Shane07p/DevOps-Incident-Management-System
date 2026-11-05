import { z } from 'zod'
import { apiFetch } from './client'
import { incidentStatusSchema, severitySchema } from './schemas'

// ---------------------------------------------------------------------------
// Schemas
// ---------------------------------------------------------------------------

/**
 * Similar incident returned by the backend (AI-1 / Issue #159 / #170).
 * Displays up to 3 similar past incidents with match percentage and how each was fixed.
 */
export const similarIncidentSchema = z.object({
  id: z.number(),
  reference: z.string(),
  title: z.string(),
  serviceName: z.string().optional(),
  severity: severitySchema.optional(),
  status: incidentStatusSchema.optional(),
  /** Similarity score (0.0 to 1.0, or 0 to 100 percentage) */
  score: z.number(),
  /** How the similar incident was resolved or fixed */
  fix: z.string().optional(),
  resolution: z.string().optional(),
})

export type SimilarIncident = z.infer<typeof similarIncidentSchema>

export const similarIncidentsResponseSchema = z.union([
  z.array(similarIncidentSchema),
  z.object({
    incidents: z.array(similarIncidentSchema),
  }).transform((val) => val.incidents),
])

/**
 * Open duplicate candidate detected during incident declaration.
 */
export const duplicateIncidentSchema = z.object({
  id: z.number(),
  reference: z.string(),
  title: z.string(),
  status: incidentStatusSchema.optional(),
  score: z.number().optional(),
})

export type DuplicateIncident = z.infer<typeof duplicateIncidentSchema>

export const duplicateCheckResponseSchema = z.union([
  z.object({
    duplicate: z.boolean(),
    incident: duplicateIncidentSchema.nullable().optional(),
    message: z.string().optional(),
  }).transform((val) => (val.duplicate && val.incident ? val.incident : null)),
  duplicateIncidentSchema.nullable(),
])

// ---------------------------------------------------------------------------
// API Functions
// ---------------------------------------------------------------------------

/**
 * GET /api/incidents/{id}/similar
 * Returns up to 3 similar past incidents above the similarity threshold.
 * If AI is disabled, unavailable, or encounters an error, fails silently returning empty array.
 */
export async function fetchSimilarIncidents(incidentId: number): Promise<SimilarIncident[]> {
  try {
    return await apiFetch(`/incidents/${incidentId}/similar`, similarIncidentsResponseSchema)
  } catch {
    // When AI service is down or AI is disabled, fail quietly without disrupting the page (FR-18.4)
    return []
  }
}

/**
 * GET /api/incidents/duplicate-check?title=...&serviceId=...
 * Checks for a likely open duplicate when declaring an incident.
 * Non-blocking check only. Fails quietly if unavailable.
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

  try {
    return await apiFetch(`/incidents/duplicate-check?${sp.toString()}`, duplicateCheckResponseSchema)
  } catch {
    return null
  }
}

