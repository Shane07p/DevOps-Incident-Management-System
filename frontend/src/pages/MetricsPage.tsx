import { useQueries, useQuery } from '@tanstack/react-query'
import { CircleAlert, RefreshCw, Terminal } from 'lucide-react'
import { useCallback, useId, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { listServiceChoices } from '@/api/incidents'
import { fetchMetric, METRIC_LABEL, METRICS } from '@/api/telemetry'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { formatIst } from '@/lib/time'
import { cn } from '@/lib/utils'
import { MetricChart } from './incident/IncidentTelemetry'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const TIME_RANGES = [
  { id: '1h', label: 'Last 1 hour', minutes: 60 },
  { id: '6h', label: 'Last 6 hours', minutes: 360 },
  { id: '24h', label: 'Last 24 hours', minutes: 1440 },
] as const

/** A 503 means Grafana is down or not set up; the server's message says which. */
function unavailable(error: unknown) {
  return error instanceof ApiRequestError && error.status === 503 ? error.message : null
}

// ---------------------------------------------------------------------------
// Main Component
// ---------------------------------------------------------------------------

/**
 * Metrics Dashboard Page (FR-27 / Issue #163).
 * Telemetry section: query service metrics (request rate, error rate, latency p50/p95)
 * across configurable time ranges, synchronized with URL search parameters.
 */
export function MetricsPage() {
  const [params, setParams] = useSearchParams()

  // ---- Read parameters from URL --------------------------------------------
  const chosenServiceId = params.get('serviceId') ?? ''
  const timeRangeParam = params.get('timeRange') ?? '1h'
  const explicitFrom = params.get('from') ?? ''
  const explicitTo = params.get('to') ?? ''

  // Time reference kept in state; updated on refresh or range change
  const [windowNow, setWindowNow] = useState(() => Date.now())

  // ---- Atomic filter updater -----------------------------------------------
  const setFilters = useCallback(
    (overrides: Partial<Record<'serviceId' | 'timeRange' | 'from' | 'to', string>>) => {
      setWindowNow(Date.now())
      setParams((prev) => {
        const next = new URLSearchParams(prev)
        for (const [k, v] of Object.entries(overrides)) {
          if (v) {
            next.set(k, v)
          } else {
            next.delete(k)
          }
        }
        return next
      })
    },
    [setParams],
  )

  // ---- Service list for dropdown -------------------------------------------
  const services = useQuery({
    queryKey: ['services-choices'],
    queryFn: listServiceChoices,
  })

  // The server reads one service's metrics at a time; until one is chosen, the first is shown.
  const serviceId = chosenServiceId || (services.data?.[0] ? String(services.data[0].id) : '')

  // ---- Time window ---------------------------------------------------------
  const rangeMinutes = TIME_RANGES.find((r) => r.id === timeRangeParam)?.minutes ?? 60

  const fromTime = explicitFrom || new Date(windowNow - rangeMinutes * 60_000).toISOString()
  const toTime = explicitFrom ? (explicitTo || new Date(windowNow).toISOString()) : new Date(windowNow).toISOString()

  // ---- Metrics queries for the 4 standard metrics --------------------------
  const metrics = useQueries({
    queries: METRICS.map((metric) => ({
      queryKey: ['telemetry', 'metric', serviceId, metric, fromTime, toTime],
      queryFn: () => fetchMetric({ serviceId: Number(serviceId), metric, from: fromTime, to: toTime }),
      enabled: serviceId !== '',
      retry: false,
    })),
  })

  const isPending = metrics.some((q) => q.isPending)
  const isFetching = metrics.some((q) => q.isFetching)

  // 503 check: returns actual server message when Grafana is not set up or down
  const metricsDown = metrics.map((query) => unavailable(query.error)).find((message) => message !== null) ?? null

  // Other non-503 error
  const generalError = metrics.find(
    (q) => q.isError && !(q.error instanceof ApiRequestError && q.error.status === 503),
  )?.error

  const refetchAll = () => {
    setWindowNow(Date.now())
    metrics.forEach((q) => q.refetch())
  }

  // ---- Accessibility IDs ---------------------------------------------------
  const serviceIdAttr = useId()
  const timeRangeIdAttr = useId()

  const logsLink = serviceId ? `/logs?serviceId=${serviceId}&timeRange=${timeRangeParam}` : '/logs'

  return (
    <div className="mx-auto flex max-w-[1240px] flex-col gap-6">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Metrics
          </h1>
          <p className="text-muted-foreground">
            Request rate, error rate, and latency across services. Times are IST.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" asChild className="gap-2 font-medium">
            <Link to={logsLink}>
              <Terminal className="size-4" aria-hidden="true" />
              Open in Logs
            </Link>
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={refetchAll}
            disabled={isFetching}
            aria-label="Refresh metrics"
            title="Refresh metrics"
          >
            <RefreshCw className={cn('size-4', isFetching && 'animate-spin')} aria-hidden="true" />
          </Button>
        </div>
      </div>

      {/* Filter controls */}
      <div className="flex flex-col gap-3 rounded-lg border bg-card p-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {/* Service filter */}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={serviceIdAttr} className="text-sm font-medium">
              Service
            </Label>
            <Select value={serviceId} onValueChange={(val) => setFilters({ serviceId: val })}>
              <SelectTrigger id={serviceIdAttr} aria-label="Service" className="w-full">
                <SelectValue placeholder={services.isPending ? 'Loading services' : 'Choose a service'} />
              </SelectTrigger>
              <SelectContent>
                {services.data?.map((svc) => (
                  <SelectItem key={svc.id} value={String(svc.id)}>
                    {svc.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Time range filter */}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={timeRangeIdAttr} className="text-sm font-medium">
              Time range
            </Label>
            <Select
              value={timeRangeParam}
              onValueChange={(val) => setFilters({ timeRange: val, from: '', to: '' })}
            >
              <SelectTrigger id={timeRangeIdAttr} aria-label="Time range" className="w-full">
                <SelectValue placeholder="Last 1 hour" />
              </SelectTrigger>
              <SelectContent>
                {TIME_RANGES.map((tr) => (
                  <SelectItem key={tr.id} value={tr.id}>
                    {tr.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Time window readout */}
          <div className="flex flex-col justify-end pb-1 text-xs text-muted-foreground sm:col-span-2">
            <span>
              Window: <span className="font-mono">{formatIst(fromTime)}</span> to{' '}
              <span className="font-mono">{formatIst(toTime)}</span>
            </span>
          </div>
        </div>
      </div>

      {/* Main Results / States */}
      {services.isSuccess && services.data.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border bg-card py-16 text-center">
          <p className="font-semibold">No services yet</p>
          <p className="text-sm text-muted-foreground">
            Metrics are read per service. An Admin adds services on the Services page.
          </p>
        </div>
      ) : services.isError ? (
        <div role="alert" className="rounded-lg border bg-card p-6 text-center text-sm">
          Services could not be loaded: {services.error.message}
        </div>
      ) : metricsDown ? (
        /* 503 Unavailable State (TC-FR-27.3a) */
        <div
          role="alert"
          className="flex flex-col items-center gap-3 rounded-lg border bg-sev-1-bg p-8 text-center text-sev-1-fg"
        >
          <CircleAlert className="size-8" aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <p className="text-base font-semibold">Metrics unavailable</p>
            <p className="text-sm">{metricsDown}. The rest of Settle keeps working.</p>
          </div>
          <Button variant="outline" size="sm" onClick={refetchAll}>
            Try again
          </Button>
        </div>
      ) : generalError ? (
        /* Generic Error State */
        <div
          role="alert"
          className="flex flex-col items-center gap-3 rounded-lg border bg-sev-1-bg p-8 text-center text-sev-1-fg"
        >
          <CircleAlert className="size-8" aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <p className="text-base font-semibold">Failed to load metrics</p>
            <p className="text-sm">{generalError.message}</p>
          </div>
          <Button variant="outline" size="sm" onClick={refetchAll}>
            Try again
          </Button>
        </div>
      ) : isPending ? (
        <LoadingState message="Loading metrics…" />
      ) : (
        /* 4 Metric Charts Grid */
        <div className="grid gap-3 sm:grid-cols-2">
          {metrics.map((query, index) => {
            const metric = METRICS[index]
            if (query.isError) {
              return (
                <p key={metric} role="alert" className="rounded-lg border bg-card p-4 text-sm text-destructive">
                  {METRIC_LABEL[metric]} could not be loaded: {query.error.message}
                </p>
              )
            }
            if (!query.data) return null
            return <MetricChart key={metric} series={query.data} />
          })}
        </div>
      )}
    </div>
  )
}
