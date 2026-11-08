import { z } from 'zod'
import { apiFetch } from './client'

/** The things an audit entry can be about, as `GET /api/audit` names them (backend `AuditItemType`). */
export const AUDIT_ITEM_TYPES = ['USER', 'SERVICE', 'INCIDENT', 'TOOL', 'ORG', 'MONITOR', 'RUNBOOK'] as const

export const auditEntrySchema = z.object({
  id: z.number(),
  // Null for changes the system made by itself (FR-13.2).
  actorDisplayName: z.string().nullable(),
  action: z.string(),
  itemType: z.string(),
  itemId: z.number(),
  details: z.string().nullable(),
  recordedAt: z.string(),
})

export type AuditEntry = z.infer<typeof auditEntrySchema>

export const auditPageSchema = z.object({
  content: z.array(auditEntrySchema),
  page: z.number(),
  size: z.number(),
  totalElements: z.number(),
})

export type AuditPage = z.infer<typeof auditPageSchema>

export interface AuditQuery {
  /** Zero-based, as the API counts pages. */
  page: number
  itemType?: string
  actorId?: string
  /** Inclusive, ISO-8601 UTC. */
  from?: string
  /** Exclusive, ISO-8601 UTC. */
  to?: string
}

/** Admin only. Newest first, 50 a page; every filter is optional. */
export function listAuditEntries(query: AuditQuery) {
  const params = new URLSearchParams({ page: String(query.page) })
  for (const key of ['itemType', 'actorId', 'from', 'to'] as const) {
    const value = query[key]
    if (value) params.set(key, value)
  }
  return apiFetch(`/audit?${params}`, auditPageSchema)
}
