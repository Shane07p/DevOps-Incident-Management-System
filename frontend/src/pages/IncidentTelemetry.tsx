
import { useQuery } from '@tanstack/react-query'
import {
  getServiceMetrics,
  type MetricName,
  type MetricPoint,
} from '@/api/telemetry'
import { fetchLogs } from '@/api/logs'
import { formatIst } from '@/lib/time'

type Props = {
  serviceId: number
  createdAt: string
}

const metrics: { name: MetricName; label: string }[] = [
  { name: 'requestRate', label: 'Request rate' },
  { name: 'errorRate', label: 'Error rate' },
  { name: 'latencyP50', label: 'Latency P50' },
  { name: 'latencyP95', label: 'Latency P95' },
]

function MetricChart({
  label,
  points,
}: {
  label: string
  points: MetricPoint[]
}) {
  if (points.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No {label.toLowerCase()} data available.
      </p>
    )
  }

  const values = points.map((point) => point.value)
  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  const range = maximum - minimum || 1

  const coordinates = points
    .map((point, index) => {
      const x =
        points.length === 1
          ? 50
          : (index / (points.length - 1)) * 100
      const y = 90 - ((point.value - minimum) / range) * 80
      return `${x},${y}`
    })
    .join(' ')

  return (
    <div className="rounded-lg border bg-card p-4">
      <h3 className="mb-2 font-medium">{label}</h3>

      <svg
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        className="h-36 w-full"
        role="img"
        aria-label={`${label} chart with ${points.length} data points`}
      >
        <polyline
          points={coordinates}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          vectorEffect="non-scaling-stroke"
        />
      </svg>

      <p className="text-xs text-muted-foreground">
        Min: {minimum.toFixed(2)} · Max: {maximum.toFixed(2)}
      </p>
    </div>
  )
}

function MetricSection({
  serviceId,
  metric,
  label,
  from,
  to,
}: {
  serviceId: number
  metric: MetricName
  label: string
  from: string
  to: string
}) {
  const query = useQuery({
    queryKey: ['telemetry', 'metrics', serviceId, metric, from, to],
    queryFn: () => getServiceMetrics(serviceId, metric, from, to),
    retry: false,
  })

  if (query.isPending) {
    return <p className="text-sm">Loading {label.toLowerCase()}...</p>
  }

  if (query.isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        Unable to load {label.toLowerCase()}.
      </p>
    )
  }

  return <MetricChart label={label} points={query.data} />
}

export function IncidentTelemetry({ serviceId, createdAt }: Props) {
  // Show the two hours leading up to incident declaration.
  const to = createdAt
  const from = new Date(
    new Date(createdAt).getTime() - 2 * 60 * 60 * 1000,
  ).toISOString()

  const logs = useQuery({
    queryKey: ['telemetry', 'logs', serviceId, from, to],
    queryFn: () => fetchLogs({ serviceId, from, to, limit: 50 }),
    retry: false,
  })

  return (
    <>
      <section
        aria-labelledby="incident-logs-heading"
        className="flex flex-col gap-3"
      >
        <h2
          id="incident-logs-heading"
          className="text-lg font-semibold"
        >
          Service Logs
        </h2>

        {logs.isPending ? (
          <p className="text-sm">Loading logs...</p>
        ) : logs.isError ? (
          <p role="alert" className="text-sm text-destructive">
            Unable to load logs. Telemetry may be unavailable.
          </p>
        ) : logs.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No logs found for this incident's time window.
          </p>
        ) : (
          <ul className="max-h-80 space-y-2 overflow-y-auto rounded-lg border bg-card p-4">
            {logs.data.map((log, index) => (
              <li
                key={`${log.timestamp}-${index}`}
                className="border-b pb-2 font-mono text-xs last:border-b-0"
              >
                <span className="text-muted-foreground">
                  {formatIst(log.timestamp)}
                </span>
                {' '}
                {log.level && <strong>{log.level} </strong>}
                <span className="break-all">{log.message}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        aria-labelledby="incident-metrics-heading"
        className="flex flex-col gap-3"
      >
        <h2
          id="incident-metrics-heading"
          className="text-lg font-semibold"
        >
          Service Metrics
        </h2>

        <div className="grid gap-4 sm:grid-cols-2">
          {metrics.map(({ name, label }) => (
            <MetricSection
              key={name}
              serviceId={serviceId}
              metric={name}
              label={label}
              from={from}
              to={to}
            />
          ))}
        </div>
      </section>
    </>
  )
}
