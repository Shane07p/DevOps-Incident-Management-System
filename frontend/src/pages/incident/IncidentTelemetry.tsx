import { useQueries, useQuery } from '@tanstack/react-query'
import { useState, type MouseEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { fetchLogs, fetchMetric, METRIC_LABEL, METRICS, type MetricName, type MetricSeries } from '@/api/telemetry'
import { formatIst } from '@/lib/time'
import { cn } from '@/lib/utils'

const HOUR_MS = 3_600_000

/**
 * The incident's window: from an hour before it was declared, to now, at most the 24 hours the server
 * allows. Worked out once per page view, so the queries keep one key.
 */
function incidentWindow(createdAt: string, now: number) {
  const from = Date.parse(createdAt) - HOUR_MS
  const to = Math.min(now, from + 24 * HOUR_MS)
  return { from: new Date(from).toISOString(), to: new Date(to).toISOString() }
}

/** requestRate is per second, errorRate a 0-1 ratio, and latency in seconds. */
function formatMetric(metric: MetricName, value: number) {
  switch (metric) {
    case 'requestRate':
      return `${value < 10 ? value.toFixed(2) : Math.round(value)} req/s`
    case 'errorRate':
      return `${(value * 100).toFixed(value < 0.1 ? 2 : 1)}%`
    default:
      return `${Math.round(value * 1000)} ms`
  }
}

const LEVEL_STYLE: Record<string, string> = {
  DEBUG: 'bg-muted text-muted-foreground',
  INFO: 'bg-status-open-bg text-status-open',
  WARN: 'bg-status-investigating-bg text-status-investigating',
  ERROR: 'bg-sev-1-bg text-sev-1-fg',
}

/** A 503 means Grafana is down or not set up; the server's message says which. */
function unavailable(error: unknown) {
  return error instanceof ApiRequestError && error.status === 503 ? error.message : null
}

const WIDTH = 300
const HEIGHT = 96

/** One metric as a line, with the moment the incident was declared marked. Hover reads a point. */
function MetricChart({ series, declaredAt }: { series: MetricSeries; declaredAt: string }) {
  const [hovered, setHovered] = useState<number | null>(null)
  const label = METRIC_LABEL[series.metric]
  const { points } = series
  const start = Date.parse(series.from)
  const span = Math.max(Date.parse(series.to) - start, 1)
  const values = points.map((point) => point.value)
  const max = Math.max(...values, 0)
  const top = max === 0 ? 1 : max
  const x = (at: string) => ((Date.parse(at) - start) / span) * WIDTH
  const y = (value: number) => HEIGHT - (value / top) * (HEIGHT - 6)
  const line = points.map((point) => `${x(point.timestamp).toFixed(1)},${y(point.value).toFixed(1)}`).join(' ')
  const declaredX = x(declaredAt)
  const latest = points.at(-1)
  const shown = hovered === null ? null : points[hovered]

  const onMove = (event: MouseEvent<SVGSVGElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    const at = start + ((event.clientX - box.left) / box.width) * span
    let nearest = 0
    for (let index = 1; index < points.length; index++) {
      if (Math.abs(Date.parse(points[index].timestamp) - at) < Math.abs(Date.parse(points[nearest].timestamp) - at)) nearest = index
    }
    setHovered(points.length > 0 ? nearest : null)
  }

  return (
    <figure className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <figcaption className="flex items-baseline justify-between gap-2">
        <span className="text-sm font-semibold">{label}</span>
        <span className="font-mono text-xs text-muted-foreground tnum">
          {shown
            ? `${formatIst(shown.timestamp)} · ${formatMetric(series.metric, shown.value)}`
            : latest
              ? `Latest ${formatMetric(series.metric, latest.value)}`
              : ''}
        </span>
      </figcaption>
      {points.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">No traffic in this window.</p>
      ) : (
        <>
          <svg
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            preserveAspectRatio="none"
            className="h-24 w-full overflow-visible text-primary"
            role="img"
            aria-label={`${label}: ${points.length} points, peak ${formatMetric(series.metric, max)}${latest ? `, latest ${formatMetric(series.metric, latest.value)}` : ''}`}
            onMouseMove={onMove}
            onMouseLeave={() => setHovered(null)}
          >
            <line x1="0" x2={WIDTH} y1={HEIGHT} y2={HEIGHT} className="stroke-border" vectorEffect="non-scaling-stroke" />
            {declaredX >= 0 && declaredX <= WIDTH && (
              <line
                x1={declaredX}
                x2={declaredX}
                y1="0"
                y2={HEIGHT}
                className="stroke-muted-foreground"
                strokeDasharray="3 3"
                vectorEffect="non-scaling-stroke"
              />
            )}
            <polyline points={line} fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            {shown && (
              <line
                x1={x(shown.timestamp)}
                x2={x(shown.timestamp)}
                y1="0"
                y2={HEIGHT}
                className="stroke-foreground/40"
                vectorEffect="non-scaling-stroke"
              />
            )}
          </svg>
          <div className="flex justify-between font-mono text-[11px] text-muted-foreground">
            <span>Peak {formatMetric(series.metric, max)}</span>
            <span>Dashed line: declared</span>
          </div>
        </>
      )}
    </figure>
  )
}

/** The service's logs and charts around the incident (FR-09, FR-26, FR-27). Grafana down never breaks the page. */
export function IncidentTelemetry({ serviceId, createdAt }: { serviceId: number; createdAt: string }) {
  const [range] = useState(() => incidentWindow(createdAt, Date.now()))
  const logs = useQuery({
    queryKey: ['telemetry', 'logs', serviceId, range],
    queryFn: () => fetchLogs({ serviceId, from: range.from, to: range.to, limit: 50 }),
    retry: false,
  })
  const metrics = useQueries({
    queries: METRICS.map((metric) => ({
      queryKey: ['telemetry', 'metric', serviceId, metric, range],
      queryFn: () => fetchMetric({ serviceId, metric, from: range.from, to: range.to }),
      retry: false,
    })),
  })
  const metricsDown = metrics.map((query) => unavailable(query.error)).find((message) => message !== null) ?? null
  const logsDown = unavailable(logs.error)
  const logsLink = `/logs?${new URLSearchParams({ serviceId: String(serviceId), from: range.from, to: range.to })}`

  return (
    <>
      <section aria-labelledby="incident-metrics-heading" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="incident-metrics-heading" className="text-lg font-semibold">
            Metrics
          </h2>
          <span className="text-xs text-muted-foreground">
            {formatIst(range.from)} to {formatIst(range.to)}
          </span>
        </div>
        {metricsDown ? (
          <p role="status" className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
            {metricsDown}. The rest of the incident works as usual.
          </p>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {metrics.map((query, index) =>
              query.isPending ? (
                <div key={METRICS[index]} className="h-[150px] animate-pulse rounded-lg border bg-muted/40" aria-hidden="true" />
              ) : query.isError ? (
                <p key={METRICS[index]} role="alert" className="rounded-lg border bg-card p-4 text-sm text-destructive">
                  {METRIC_LABEL[METRICS[index]]} could not be loaded: {query.error.message}
                </p>
              ) : (
                <MetricChart key={METRICS[index]} series={query.data} declaredAt={createdAt} />
              ),
            )}
          </div>
        )}
      </section>

      <section aria-labelledby="incident-logs-heading" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="incident-logs-heading" className="text-lg font-semibold">
            Logs
          </h2>
          <Link to={logsLink} className="text-sm font-medium text-primary hover:underline">
            Open in Logs
          </Link>
        </div>
        {logs.isPending ? (
          <div className="h-24 animate-pulse rounded-lg border bg-muted/40" aria-hidden="true" />
        ) : logsDown ? (
          <p role="status" className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
            {logsDown}. The rest of the incident works as usual.
          </p>
        ) : logs.isError ? (
          <p role="alert" className="rounded-lg border bg-card p-4 text-sm text-destructive">
            Logs could not be loaded: {logs.error.message}
          </p>
        ) : logs.data.length === 0 ? (
          <p className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">No log lines in this window.</p>
        ) : (
          <ol className="max-h-80 divide-y overflow-y-auto rounded-lg border bg-card font-mono text-xs">
            {logs.data.map((log) => (
              <li key={log.id} className="flex flex-wrap items-baseline gap-2 px-3 py-2">
                <span className="text-muted-foreground tnum">{formatIst(log.timestamp)}</span>
                <span className={cn('rounded px-1.5 py-0.5 text-[10.5px] font-semibold', LEVEL_STYLE[log.level] ?? LEVEL_STYLE.DEBUG)}>
                  {log.level}
                </span>
                <span className="min-w-0 flex-1 break-all text-foreground/90">{log.message}</span>
              </li>
            ))}
          </ol>
        )}
      </section>
    </>
  )
}
