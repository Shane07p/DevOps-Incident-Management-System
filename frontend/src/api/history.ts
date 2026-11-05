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
  serviceId: z.number().optional(),
  severity: severitySchema,
  status: incidentStatusSchema,
  createdAt: z.iso.datetime({ offset: true }),
  /** Where the keyword matched – omitted when no keyword was given. */
  matchedIn: z.array(matchLocationSchema).optional(),
})

export type HistorySearchResult = z.infer<typeof historySearchResultSchema>

/**
 * IncidentSearchPageResponse contract from backend PR #209:
 * content, page (0-based), size, totalElements, totalPages.
 */
export const historySearchResponseSchema = z.object({
  content: z.array(historySearchResultSchema),
  page: z.number(),
  size: z.number(),
  totalElements: z.number(),
  totalPages: z.number(),
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
  /** Zero-based page number expected by backend PR #209 */
  page?: number
}

/** Build the query string for GET /api/incidents/search */
export function buildSearchQuery(params: HistorySearchParams): string {
  const sp = new URLSearchParams()
  // Omit q entirely when empty or not provided (Shane's review fix 3)
  if (params.q && params.q.trim()) {
    sp.set('q', params.q.trim())
  }
  if (params.serviceId) sp.set('serviceId', params.serviceId)
  if (params.severity) sp.set('severity', params.severity)
  if (params.status) sp.set('status', params.status)
  // Zero-based page number sent to backend (Shane's review fix 2)
  if (params.page !== undefined) sp.set('page', String(params.page))
  const qs = sp.toString()
  return qs ? `?${qs}` : ''
}

// ---------------------------------------------------------------------------
// API function
// ---------------------------------------------------------------------------

/**
 * GET /api/incidents/search
 * Backend issue #158 / PR #209 provides full-text and filtered incident history search.
 * Backend contract: page is 0-based, response envelopes results in `content` with pagination metadata.
 * Note: PR #209 review points pending on backend: optional `q` parameter and `serviceId` in `IncidentSearchResult`.
 */
export function searchHistory(params: HistorySearchParams): Promise<HistorySearchResponse> {
  return apiFetch(`/incidents/search${buildSearchQuery(params)}`, historySearchResponseSchema)
}
