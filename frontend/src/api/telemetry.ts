import { z } from 'zod'
import { apiFetch } from './client'

// A service's logs and metrics, read by the backend from Grafana with its own token (D34, #156).
// The browser never calls Grafana. Both answer 503 when Grafana is slow, down, or not set up.

const timestamp = z.iso.datetime({ offset: true })

/** The levels the server filters on. */
export const LOG_LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR'] as const
export type LogLevel = (typeof LOG_LEVELS)[number]

/** One log line (FR-26.1): the line's message, or the whole line when it is not JSON, and its other JSON fields. */
export const logEntrySchema = z.object({
  id: z.string(),
  timestamp,
  serviceId: z.number(),
  serviceName: z.string(),
  level: z.string(),
  message: z.string(),
  attributes: z.record(z.string(), z.unknown()),
})

export type LogEntry = z.infer<typeof logEntrySchema>

export interface LogsQuery {
  serviceId: number
  level?: LogLevel
  /** Matched literally, never as query syntax. */
  text?: string
  /** ISO-8601 UTC. The server's default is an hour before `to`; the range is at most 24 hours. */
  from?: string
  /** ISO-8601 UTC. The server's default is now. */
  to?: string
  /** 1 to 1000; the server's default is 100. */
  limit?: number
}

export const METRICS = ['requestRate', 'errorRate', 'latencyP50', 'latencyP95'] as const
export type MetricName = (typeof METRICS)[number]

export const METRIC_LABEL: Record<MetricName, string> = {
  requestRate: 'Request rate',
  errorRate: 'Error rate',
  latencyP50: 'Latency p50',
  latencyP95: 'Latency p95',
}

/** One metric of one service (FR-27.1): a point every `stepSeconds`; a moment with no traffic has no point. */
export const metricSeriesSchema = z.object({
  metric: z.enum(METRICS),
  serviceId: z.number(),
  serviceName: z.string(),
  from: timestamp,
  to: timestamp,
  stepSeconds: z.number(),
  points: z.array(z.object({ timestamp, value: z.number() })),
})

export type MetricSeries = z.infer<typeof metricSeriesSchema>

export interface MetricQuery {
  serviceId: number
  metric: MetricName
  from?: string
  to?: string
  /** Seconds, or 30s, 5m, 1h; 15 seconds to an hour. The server's default is 60s. */
  step?: string
}

function query(params: object) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value))
  }
  return search.toString()
}

/** A service's log lines, newest first (FR-26). Every role may read them. */
export function fetchLogs(params: LogsQuery) {
  return apiFetch(`/telemetry/logs?${query(params)}`, z.array(logEntrySchema))
}

/** One metric of a service over a time range (FR-27). Every role may read it. */
export function fetchMetric(params: MetricQuery) {
  return apiFetch(`/telemetry/metrics?${query(params)}`, metricSeriesSchema)
}
