import { z } from 'zod'
import { apiFetch } from './client'

const timestamp = z.iso.datetime({ offset: true })

/** What customers are told about an incident; separate from the internal incident status (FR-21). */
export const PUBLIC_UPDATE_STATUSES = ['INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED'] as const
export const publicUpdateStatusSchema = z.enum(PUBLIC_UPDATE_STATUSES)
export type PublicUpdateStatus = z.infer<typeof publicUpdateStatusSchema>

export const PUBLIC_UPDATE_LABEL: Record<PublicUpdateStatus, string> = {
  INVESTIGATING: 'Investigating',
  IDENTIFIED: 'Identified',
  MONITORING: 'Monitoring',
  RESOLVED: 'Resolved',
}

/** A published update as customers see it: no author, no internal fields. */
export const publicUpdateSchema = z.object({
  id: z.number(),
  status: publicUpdateStatusSchema,
  message: z.string(),
  createdAt: timestamp,
})

export type PublicUpdate = z.infer<typeof publicUpdateSchema>

const healthSchema = z.enum(['UNKNOWN', 'OPERATIONAL', 'DEGRADED', 'DOWN'])
export type PublicHealth = z.infer<typeof healthSchema>

const publishedServiceSchema = z.object({
  id: z.number(),
  // The service's public name, never its internal one.
  name: z.string(),
  health: healthSchema,
  // Null while no check has run in the last 90 days.
  uptimePercent: z.number().nullable(),
  // The last 90 days in UTC, oldest first; a day with no check is null.
  uptimeDays: z.array(z.object({ date: z.iso.date(), uptimePercent: z.number().nullable() })),
})

export type PublishedService = z.infer<typeof publishedServiceSchema>

const publishedIncidentSchema = z.object({
  id: z.number(),
  serviceName: z.string(),
  status: publicUpdateStatusSchema,
  // Newest first.
  updates: z.array(publicUpdateSchema),
  // Null while the incident is active.
  resolvedAt: timestamp.nullable(),
})

export type PublishedIncident = z.infer<typeof publishedIncidentSchema>

export const publicStatusSchema = z.object({
  orgName: z.string(),
  services: z.array(publishedServiceSchema),
  activeIncidents: z.array(publishedIncidentSchema),
  resolvedIncidents: z.array(publishedIncidentSchema),
})

export type PublicStatus = z.infer<typeof publicStatusSchema>

/** An org's public page. Needs no login; the server ignores any token sent with it. */
export function getPublicStatus(slug: string) {
  return apiFetch(`/public/status/${encodeURIComponent(slug)}`, publicStatusSchema)
}

/** The incident's Lead or Comms, or an Admin. The service must be on the status page, or the server answers 409. */
export function postPublicUpdate(incidentId: number, update: { status: PublicUpdateStatus; message: string }) {
  return apiFetch(`/incidents/${incidentId}/public-updates`, publicUpdateSchema, { method: 'POST', body: update })
}
