import { z } from 'zod'
import { apiFetch } from './client'
import { incidentStatusSchema, severitySchema, type IncidentStatus, type Severity } from './schemas'

const timestamp = z.iso.datetime({ offset: true })
const personSchema = z.object({ id: z.number(), displayName: z.string() })

export const incidentSummarySchema = z.object({
  id: z.number(),
  reference: z.string(),
  title: z.string(),
  serviceName: z.string(),
  severity: severitySchema,
  status: incidentStatusSchema,
  createdAt: timestamp,
  // The board sorts by time left; which of the two clocks still runs follows from the status.
  responseDueAt: timestamp,
  resolutionDueAt: timestamp,
})

export type IncidentSummary = z.infer<typeof incidentSummarySchema>

export const timelineEntrySchema = z.object({
  id: z.number(),
  // A string, not an enum: later sprints add event types, and an older page should still show them.
  eventType: z.string(),
  // System actions, such as an alert opening an incident, have no actor.
  actor: z.string().nullable(),
  details: z.string().nullable(),
  recordedAt: timestamp,
  correctsEntryId: z.number().nullable(),
})

export type TimelineEntry = z.infer<typeof timelineEntrySchema>

export const deploySchema = z.object({
  id: z.number(),
  version: z.string(),
  commit: z.string(),
  author: z.string(),
  deployedAt: timestamp,
})

export type Deploy = z.infer<typeof deploySchema>

export const incidentSchema = z.object({
  id: z.number(),
  reference: z.string(),
  title: z.string(),
  description: z.string(),
  service: z.object({ id: z.number(), name: z.string() }),
  severity: severitySchema,
  status: incidentStatusSchema,
  // Sent back as expectedVersion on every change, so a change made from an out-of-date screen gets 409 (NFR-11.2).
  version: z.number(),
  // Null for incidents the system opens.
  reporter: personSchema.nullable(),
  // Null until on-call assignment or an acknowledgement.
  assignee: personSchema.nullable(),
  // The Lead is the assignee (D39), so only Comms and Ops are here.
  roles: z.object({ comms: personSchema.nullable(), ops: personSchema.nullable() }).default({ comms: null, ops: null }),
  // Set while one person holds two roles on a SEV1 or SEV2 incident (FR-05.5).
  roleWarning: z.string().nullable().default(null),
  createdAt: timestamp,
  responseTargetMinutes: z.number(),
  resolutionTargetMinutes: z.number(),
  responseDueAt: timestamp,
  resolutionDueAt: timestamp,
  // When each clock stopped: the first accepted acknowledgement, and RESOLVED. Null while it runs.
  acknowledgedAt: timestamp.nullable().default(null),
  resolvedAt: timestamp.nullable().default(null),
  timeline: z.array(timelineEntrySchema),
  deploys: z.array(deploySchema).default([]),
})

export type Incident = z.infer<typeof incidentSchema>

/** The body of POST /api/incidents. The reporter and time come from the request, not the body. */
export interface NewIncident {
  title: string
  description: string
  serviceId: number
  severity: Severity
}

export const serviceChoiceSchema = z.object({ id: z.number(), name: z.string() })

/** The services an incident can be declared against: only the id and name the declare form needs. */
export function listServiceChoices() {
  return apiFetch('/services', z.array(serviceChoiceSchema))
}

/** Every role may read incidents; the server lists them newest first. */
export function listIncidents() {
  return apiFetch('/incidents', z.array(incidentSummarySchema))
}

export function getIncident(id: number) {
  return apiFetch(`/incidents/${id}`, incidentSchema)
}

/** Admins and Engineers only. */
export function createIncident(incident: NewIncident) {
  return apiFetch('/incidents', incidentSchema, { method: 'POST', body: incident })
}

/** Admins and Engineers only. A comment is its own timeline entry: it needs no incident version, and CLOSED incidents take them too. */
export function addComment(incidentId: number, comment: string) {
  return apiFetch(`/incidents/${incidentId}/comments`, timelineEntrySchema, { method: 'POST', body: { comment } })
}

/** Admins and Engineers only. Adds a new entry linked to the one it corrects; the original never changes. */
export function correctTimelineEntry(incidentId: number, entryId: number, details: string) {
  return apiFetch(`/incidents/${incidentId}/timeline/${entryId}/corrections`, timelineEntrySchema, {
    method: 'POST',
    body: { details },
  })
}

/** Admins and Engineers only. Recorded on the timeline as "label: url"; CLOSED incidents take evidence too. */
export function addEvidence(incidentId: number, evidence: { label: string; url: string }) {
  return apiFetch(`/incidents/${incidentId}/evidence`, timelineEntrySchema, { method: 'POST', body: evidence })
}

/** Links an existing deployment to an incident. Admins and Engineers only. */
export function linkDeploy(incidentId: number, deployId: number) {
  return apiFetch(`/incidents/${incidentId}/deploys/${deployId}`, timelineEntrySchema, { method: 'POST' })
}

// Every change below sends the version the screen showed; the server answers 409 if the incident changed since.

/** Engineers and Admins, on an OPEN incident only. The acknowledger becomes the assignee and the response clock stops (#83). */
export function acknowledgeIncident(incidentId: number, expectedVersion: number) {
  return apiFetch(`/incidents/${incidentId}/acknowledge`, incidentSchema, { method: 'POST', body: { expectedVersion } })
}

/** Only the legal next statuses are accepted; RESOLVED needs a recovery note (#84). */
export function changeStatus(incidentId: number, change: { status: IncidentStatus; note: string; expectedVersion: number }) {
  return apiFetch(`/incidents/${incidentId}/transitions`, incidentSchema, { method: 'POST', body: change })
}

/** Needs a reason. The SLA targets stay as they were copied at creation (#84). */
export function changeSeverity(incidentId: number, change: { severity: Severity; reason: string; expectedVersion: number }) {
  return apiFetch(`/incidents/${incidentId}/severity`, incidentSchema, { method: 'PUT', body: change })
}

/** The new assignee is the Lead and is notified; neither clock restarts. */
export function reassignIncident(incidentId: number, change: { assigneeId: number; expectedVersion: number }) {
  return apiFetch(`/incidents/${incidentId}/assignee`, incidentSchema, { method: 'PUT', body: change })
}

/** Comms or Ops; the Lead changes through reassignment (D39). */
export function assignIncidentRole(incidentId: number, change: { role: 'COMMS' | 'OPS'; userId: number; expectedVersion: number }) {
  return apiFetch(`/incidents/${incidentId}/roles`, incidentSchema, { method: 'PUT', body: change })
}
