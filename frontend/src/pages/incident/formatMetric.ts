import type { MetricName } from '@/api/telemetry'

/** requestRate is per second, errorRate a 0-1 ratio, and latency in seconds; each is shown with its unit. */
export function formatMetric(metric: MetricName, value: number) {
  switch (metric) {
    case 'requestRate':
      return `${value < 10 ? value.toFixed(2) : Math.round(value)} req/s`
    case 'errorRate':
      return `${(value * 100).toFixed(value < 0.1 ? 2 : 1)}%`
    default:
      return `${Math.round(value * 1000)} ms`
  }
}
