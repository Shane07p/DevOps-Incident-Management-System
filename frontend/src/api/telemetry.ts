
import { z } from 'zod'
import { apiFetch } from './client'

// Temporary response schemas until backend #156 is finalized.
export const logEntrySchema = z.object({
  timestamp: z.string(),
  level: z.string().optional(),
  message: z.string(),
})

export const logsResponseSchema = z.array(logEntrySchema)

export const metricPointSchema = z.object({
  timestamp: z.string(),
  value: z.number(),
})

export const metricsResponseSchema = z.array(metricPointSchema)

export type LogEntry = z.infer<typeof logEntrySchema>
export type MetricPoint = z.infer<typeof metricPointSchema>

export type MetricName =
  | 'requestRate'
  | 'errorRate'
  | 'latencyP50'
  | 'latencyP95'

export function getServiceLogs(
  serviceId: number,
  from: string,
  to: string,
) {
  const params = new URLSearchParams({
    serviceId: String(serviceId),
    from,
    to,
    limit: '50',
  })

  return apiFetch(
    `/telemetry/logs?${params.toString()}`,
    logsResponseSchema,
  )
}

export function getServiceMetrics(
  serviceId: number,
  metric: MetricName,
  from: string,
  to: string,
) {
  const params = new URLSearchParams({
    serviceId: String(serviceId),
    metric,
    from,
    to,
    step: '60',
  })

  return apiFetch(
    `/telemetry/metrics?${params.toString()}`,
    metricsResponseSchema,
  )
}
