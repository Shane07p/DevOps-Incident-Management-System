import { z } from 'zod'
import { apiFetch } from './client'
import { incidentStatusSchema, severitySchema } from './schemas'

// ---------------------------------------------------------------------------
// Schema
// ---------------------------------------------------------------------------

/** Which part of an incident the search matched: title, description, or timeline. */
export const matchLocationSchema = z.enum(['title', 'description', 'timeline'])
export type MatchLocation = z.infer<typeof matchLocationSchema>

export const historySearchResultSchema = z.object({
  id: z.number(),
  reference: z.string(),
  title: z.string(),
  serviceName: z.string(),
  serviceId: z.number(),
  severity: severitySchema,
  status: incidentStatusSchema,
  createdAt: z.iso.datetime({ offset: true }),
  /** Where the keyword matched – omitted when no keyword was given. */
  matchedIn: z.array(matchLocationSchema).optional(),
})

export type HistorySearchResult = z.infer<typeof historySearchResultSchema>

export const historySearchResponseSchema = z.object({
  results: z.array(historySearchResultSchema),
  total: z.number(),
  page: z.number(),
  pageSize: z.number(),
})

export type HistorySearchResponse = z.infer<typeof historySearchResponseSchema>

// ---------------------------------------------------------------------------
// Query params
// ---------------------------------------------------------------------------

export interface HistorySearchParams {
  q?: string
  serviceId?: string
  severity?: string
  status?: string
  page?: number
}

/** Build the query string for GET /api/incidents/search */
export function buildSearchQuery(params: HistorySearchParams): string {
  const sp = new URLSearchParams()
  if (params.q) sp.set('q', params.q)
  if (params.serviceId) sp.set('serviceId', params.serviceId)
  if (params.severity) sp.set('severity', params.severity)
  if (params.status) sp.set('status', params.status)
  if (params.page && params.page > 1) sp.set('page', String(params.page))
  const qs = sp.toString()
  return qs ? `?${qs}` : ''
}

// ---------------------------------------------------------------------------
// API function
// ---------------------------------------------------------------------------

/**
 * GET /api/incidents/search
 * Backend issue #158 owns the full-text search implementation.
 * This function wires the documented contract; replace the mock or add the
 * real endpoint when #158 ships.
 */
export function searchHistory(params: HistorySearchParams): Promise<HistorySearchResponse> {
  return apiFetch(`/incidents/search${buildSearchQuery(params)}`, historySearchResponseSchema)
}

