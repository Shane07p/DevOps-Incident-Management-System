import { z } from 'zod'
import { apiFetch } from './client'

// ---------------------------------------------------------------------------
// Schemas
// ---------------------------------------------------------------------------

export const logLevelSchema = z.enum(['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'])
export type LogLevel = z.infer<typeof logLevelSchema>

export const logEntrySchema = z.object({
  id: z.union([z.string(), z.number()]).transform(String),
  timestamp: z.iso.datetime({ offset: true }),
  serviceName: z.string().default(''),
  serviceId: z.number().optional(),
  level: z.string(),
  message: z.string(),
  attributes: z.record(z.string(), z.unknown()).optional(),
})

export type LogEntry = z.infer<typeof logEntrySchema>

/** Accepts an array of log entries or an enveloped `{ logs: [...] }` */
export const logsResponseSchema = z.union([
  z.array(logEntrySchema),
  z.object({
    logs: z.array(logEntrySchema),
  }).transform((val) => val.logs),
])

// ---------------------------------------------------------------------------
// Query Parameters
// ---------------------------------------------------------------------------

export interface LogsQueryParams {
  serviceId?: string | number
  level?: string
  text?: string
  from?: string
  to?: string
  limit?: number
}

/**
 * Builds the query string for GET /api/telemetry/logs?serviceId=&level=&text=&from=&to=&limit=
 * Omits empty or unset parameters.
 */
export function buildLogsQuery(params: LogsQueryParams): string {
  const sp = new URLSearchParams()
  if (params.serviceId !== undefined && params.serviceId !== '' && params.serviceId !== '_all') {
    sp.set('serviceId', String(params.serviceId))
  }
  if (params.level && params.level !== '_all') {
    sp.set('level', params.level)
  }
  if (params.text && params.text.trim()) {
    sp.set('text', params.text.trim())
  }
  if (params.from) {
    sp.set('from', params.from)
  }
  if (params.to) {
    sp.set('to', params.to)
  }
  if (params.limit && params.limit > 0) {
    sp.set('limit', String(params.limit))
  }
  const qs = sp.toString()
  return qs ? `?${qs}` : ''
}

// ---------------------------------------------------------------------------
// API Function
// ---------------------------------------------------------------------------

/**
 * GET /api/telemetry/logs
 * The backend queries Grafana Loki using its own read token (D34 / Issue #156).
 * The browser never queries Grafana directly.
 */
export function fetchLogs(params: LogsQueryParams): Promise<LogEntry[]> {
  return apiFetch(`/telemetry/logs${buildLogsQuery(params)}`, logsResponseSchema)
}

// ---------------------------------------------------------------------------
// Helpers & Constants
// ---------------------------------------------------------------------------

export const TIME_RANGES = [
  { id: '15m', label: 'Last 15 minutes', minutes: 15 },
  { id: '1h', label: 'Last 1 hour', minutes: 60 },
  { id: '6h', label: 'Last 6 hours', minutes: 360 },
  { id: '24h', label: 'Last 24 hours', minutes: 1440 },
  { id: 'all', label: 'All time', minutes: 0 },
] as const

/**
 * Safely extracts structured key-value attributes from a log entry.
 * Checks attributes first, then attempts JSON.parse on message.
 * Handles plain text and malformed JSON without throwing.
 */
export function extractJsonFields(log: LogEntry): Record<string, unknown> | null {
  if (log.attributes && typeof log.attributes === 'object' && Object.keys(log.attributes).length > 0) {
    return log.attributes
  }
  const trimmed = log.message.trim()
  if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
    try {
      const parsed = JSON.parse(trimmed)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>
      }
    } catch {
      // Malformed JSON ΓÇö gracefully fall back to null
      return null
    }
  }
  return null
}

