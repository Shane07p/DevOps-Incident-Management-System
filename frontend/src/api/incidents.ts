import { z } from 'zod'
import { apiFetch } from './client'
import { incidentStatusSchema, severitySchema, type Severity } from './schemas'

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
  // Null for incidents the system opens.
  reporter: personSchema.nullable(),
  // Null until on-call assignment or an acknowledgement.
  assignee: personSchema.nullable(),
  // Who holds Comms and Ops; the Lead is the assignee (D39).
  roles: z.object({ comms: personSchema.nullable(), ops: personSchema.nullable() }).optional(),
  createdAt: timestamp,
  responseTargetMinutes: z.number(),
  resolutionTargetMinutes: z.number(),
  responseDueAt: timestamp,
  resolutionDueAt: timestamp,
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
