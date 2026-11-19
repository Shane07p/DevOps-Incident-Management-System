import { useQueries, useQuery } from '@tanstack/react-query'
import { RefreshCw, Terminal } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { listServiceChoices } from '@/api/incidents'
import { fetchMetric, METRICS, type MetricName, type MetricSeries } from '@/api/telemetry'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { formatIst, formatIstClock } from '@/lib/time'
import { cn } from '@/lib/utils'
import { formatMetric } from './incident/formatMetric'
import { MetricChart } from './incident/IncidentTelemetry'

const TIME_RANGES = [
  { id: '1h', label: 'Last 1 hour', minutes: 60 },
  { id: '6h', label: 'Last 6 hours', minutes: 360 },
  { id: '24h', label: 'Last 24 hours', minutes: 1440 },
] as const

/** The three numbers a responder reads first, each the metric's latest point. */
const TILES: { metric: MetricName; label: string }[] = [
  { metric: 'errorRate', label: 'Error rate' },
  { metric: 'latencyP95', label: 'p95 latency' },
  { metric: 'requestRate', label: 'Requests' },
]

/** A 503 means Grafana is down or not set up; the server's message says which. */
function unavailable(error: unknown) {
  return error instanceof ApiRequestError && error.status === 503 ? error.message : null
}

function Tile({ label, series }: { label: string; series: MetricSeries | undefined }) {
  const latest = series?.points.at(-1)
  return (
    <div className="flex flex-col gap-3 px-5 py-5">
      <span className="text-[12px] text-muted-foreground">{label}</span>
      <span className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
        <strong className="font-mono text-[30px] leading-none font-medium tnum">
          {series && latest ? formatMetric(series.metric, latest.value) : '–'}
        </strong>
        <span className="text-[12px] text-muted-foreground">
          {latest ? `at ${formatIstClock(Date.parse(latest.timestamp))} IST` : 'no traffic'}
        </span>
      </span>
    </div>
  )
}

/**
 * Metrics (FR-27, #163): one service's request rate, error rate and latency over a chosen range. The service
 * and range live in the URL; a value that isn't an option falls back to the first service and the last hour.
 */
export function MetricsPage() {
  const [params, setParams] = useSearchParams()
  // The window ends when the page opened, a filter changed, or Refresh was clicked; a new end is a new query key.
  const [windowEnd, setWindowEnd] = useState(() => Date.now())

  const services = useQuery({ queryKey: ['services-choices'], queryFn: listServiceChoices })
  const choices = services.data ?? []
  const service = choices.find((choice) => String(choice.id) === params.get('serviceId')) ?? choices[0]
  const serviceId = service ? String(service.id) : ''
  const range = TIME_RANGES.find((option) => option.id === params.get('timeRange')) ?? TIME_RANGES[0]

  const from = new Date(windowEnd - range.minutes * 60_000).toISOString()
  const to = new Date(windowEnd).toISOString()

  const metrics = useQueries({
    queries: METRICS.map((metric) => ({
      queryKey: ['telemetry', 'metric', serviceId, metric, from, to],
      queryFn: () => fetchMetric({ serviceId: Number(serviceId), metric, from, to }),
      enabled: serviceId !== '',
      retry: false,
    })),
  })
  const series = Object.fromEntries(METRICS.map((metric, index) => [metric, metrics[index].data])) as Record<
    MetricName,
    MetricSeries | undefined
  >
  const isFetching = metrics.some((query) => query.isFetching)
  const metricsDown = metrics.map((query) => unavailable(query.error)).find((message) => message !== null) ?? null
  const otherError = metrics.find((query) => query.isError && unavailable(query.error) === null)?.error

  const refresh = () => setWindowEnd(Date.now())
  const choose = (key: 'serviceId' | 'timeRange', value: string) => {
    refresh()
    setParams((previous) => {
      const next = new URLSearchParams(previous)
      next.set(key, value)
      return next
    })
  }

  const logsLink = serviceId ? `/logs?serviceId=${serviceId}&timeRange=${range.id}` : '/logs'

  let body
  if (services.isError) {
    body = <ErrorState title="Services could not be loaded" message={services.error.message} onRetry={() => services.refetch()} />
  } else if (services.isSuccess && choices.length === 0) {
    body = (
      <Panel>
        <div className="flex flex-col items-center gap-2 px-6 py-16 text-center">
          <p className="font-medium">No services yet</p>
          <p className="text-[13px] text-muted-foreground">Metrics are read per service. An Admin adds services on the Services page.</p>
        </div>
      </Panel>
    )
  } else if (metricsDown) {
    body = <ErrorState title="Metrics unavailable" message={`${metricsDown}. The rest of Settle keeps working.`} onRetry={refresh} />
  } else if (otherError) {
    body = <ErrorState title="Failed to load metrics" message={otherError.message} onRetry={refresh} />
  } else if (metrics.some((query) => query.isPending)) {
    body = <LoadingState message="Loading metrics…" />
  } else {
    body = (
      <div className="flex flex-col gap-4">
        <section
          aria-label="Latest values"
          className="grid divide-y rounded-lg border bg-card shadow-elev-1 md:grid-cols-3 md:divide-x md:divide-y-0"
        >
          {TILES.map((tile) => (
            <Tile key={tile.metric} label={tile.label} series={series[tile.metric]} />
          ))}
        </section>
        <Panel title={`${service?.name} · ${range.label.toLowerCase()}`}>
          <div className="grid gap-px bg-border sm:grid-cols-2">
            {METRICS.map((metric) => {
              const data = series[metric]
              return data ? (
                <MetricChart key={metric} series={data} className="rounded-none border-0" />
              ) : null
            })}
          </div>
          <PanelFooter>
            <span>
              From {formatIst(from)} to {formatIst(to)}
            </span>
          </PanelFooter>
        </Panel>
      </div>
    )
  }

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Metrics"
        description="Request rate, error rate and latency for one service. Times are IST."
        actions={
          <>
            <div className="flex items-center gap-2">
              <Label htmlFor="serviceId" className="text-[12px] font-normal text-muted-foreground">
                Service
              </Label>
              <Select value={serviceId} onValueChange={(value) => choose('serviceId', value)}>
                <SelectTrigger id="serviceId" className="w-[180px]">
                  <SelectValue placeholder={services.isPending ? 'Loading services' : 'Choose a service'} />
                </SelectTrigger>
                <SelectContent>
                  {choices.map((choice) => (
                    <SelectItem key={choice.id} value={String(choice.id)}>
                      {choice.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <Label htmlFor="timeRange" className="text-[12px] font-normal text-muted-foreground">
                Time range
              </Label>
              <Select value={range.id} onValueChange={(value) => choose('timeRange', value)}>
                <SelectTrigger id="timeRange" className="w-[150px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {TIME_RANGES.map((option) => (
                    <SelectItem key={option.id} value={option.id}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <Button variant="outline" size="sm" asChild>
              <Link to={logsLink}>
                <Terminal aria-hidden="true" />
                Open in Logs
              </Link>
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={refresh}
              disabled={isFetching}
              aria-label="Refresh metrics"
              title="Refresh metrics"
            >
              <RefreshCw className={cn(isFetching && 'animate-spin')} aria-hidden="true" />
            </Button>
          </>
        }
      />
      {body}
    </div>
  )
}
