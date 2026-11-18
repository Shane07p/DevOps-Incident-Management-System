import { z } from 'zod'
import { apiFetch } from './client'
import { severitySchema } from './schemas'

export type InsightsDays = 7 | 30 | 90

export const mttxMetricsSchema = z.object({
  mean: z.number().nullable(),
  median: z.number().nullable(),
  p90: z.number().nullable(),
})

export type MttxMetrics = z.infer<typeof mttxMetricsSchema>

export const incidentCountsSchema = z.object({
  total: z.number(),
  open: z.number(),
  resolved: z.number(),
})

export type IncidentCounts = z.infer<typeof incidentCountsSchema>

export const severityBreakdownSchema = z.object({
  severity: severitySchema,
  count: z.number(),
})

export type SeverityBreakdown = z.infer<typeof severityBreakdownSchema>

export const serviceBreakdownSchema = z.object({
  serviceId: z.number(),
  name: z.string(),
  count: z.number(),
})

export type ServiceBreakdown = z.infer<typeof serviceBreakdownSchema>

export const slaBreachRateSchema = z.object({
  response: z.number().nullable(),
  resolution: z.number().nullable(),
})

export type SlaBreachRate = z.infer<typeof slaBreachRateSchema>

export const followUpMetricsSchema = z.object({
  total: z.number(),
  done: z.number(),
  overdue: z.number(),
  completionRate: z.number().nullable(),
})

export type FollowUpMetrics = z.infer<typeof followUpMetricsSchema>

export const insightsResponseSchema = z.object({
  from: z.string(),
  to: z.string(),
  incidents: incidentCountsSchema,
  mtta: mttxMetricsSchema,
  mttr: mttxMetricsSchema,
  bySeverity: z.array(severityBreakdownSchema),
  byService: z.array(serviceBreakdownSchema),
  slaBreachRate: slaBreachRateSchema,
  followUps: followUpMetricsSchema,
  outOfHoursPages: z.number(),
})

export type InsightsResponse = z.infer<typeof insightsResponseSchema>

/**
 * Fetches analytics insights for the given lookback window (7, 30, or 90 days).
 */
export async function fetchInsights(days: InsightsDays = 30): Promise<InsightsResponse> {
  return apiFetch<InsightsResponse>(`/insights?days=${days}`, insightsResponseSchema)
}
