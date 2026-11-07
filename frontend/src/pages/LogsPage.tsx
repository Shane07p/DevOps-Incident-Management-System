import { useQuery } from '@tanstack/react-query'
import { CircleAlert, FileText, RefreshCw, Search, X } from 'lucide-react'
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { listServiceChoices } from '@/api/incidents'
import { fetchLogs, extractJsonFields, TIME_RANGES, type LogsQueryParams } from '@/api/logs'
import { LoadingState } from '@/components/app/LoadingState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const LOG_LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'] as const

/** Minimum polling interval for live tail (ms). Must not hammer Loki on free tier. */
const LIVE_TAIL_INTERVAL_MS = 5000

const LEVEL_STYLE: Record<string, string> = {
  DEBUG: 'bg-muted text-muted-foreground',
  INFO: 'bg-status-open-bg text-status-open',
  WARN: 'bg-status-investigating-bg text-status-investigating',
  ERROR: 'bg-sev-1-bg text-sev-1-fg',
  FATAL: 'bg-sev-1-bg text-sev-1-fg font-bold',
}

/** A chosen range's `from` is the moment of the request, minus the range; an explicit `from` stays. */
function withWindow(params: LogsQueryParams, rangeMinutes: number): LogsQueryParams {
  return params.from ? params : { ...params, from: new Date(Date.now() - rangeMinutes * 60_000).toISOString() }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function LevelBadge({ level }: { level: string }) {
  const normalized = level.toUpperCase()
  const style = LEVEL_STYLE[normalized] ?? 'bg-muted text-muted-foreground'
  return (
    <span
      className={cn(
        'inline-flex h-[20px] items-center rounded px-1.5 font-mono text-[10.5px] leading-none font-semibold',
        style,
      )}
    >
      {normalized}
    </span>
  )
}

// ---------------------------------------------------------------------------
// Main Component
// ---------------------------------------------------------------------------

/**
 * Logs Explorer Page (US-28 / Issue #163).
 * Telemetry section: query service logs with level, text, and time range filters,
 * live tail with bounded polling, and clickable JSON attributes.
 */
export function LogsPage() {
  const [params, setParams] = useSearchParams()

  // ---- Read parameters from URL --------------------------------------------
  const serviceId = params.get('serviceId') ?? ''
  const level = params.get('level') ?? ''
  const textParam = params.get('text') ?? ''
  const timeRangeParam = params.get('timeRange') ?? '1h'
  const explicitFrom = params.get('from') ?? ''
  const explicitTo = params.get('to') ?? ''

  // ---- Controlled text input (debounced to URL) -----------------------------
  const [inputText, setInputText] = useState(textParam)
  const prevTextParam = useRef(textParam)
  if (prevTextParam.current !== textParam) {
    prevTextParam.current = textParam
    setInputText(textParam)
  }

  // Active JSON field filter chip state
  const [fieldFilter, setFieldFilter] = useState<{ key: string; value: string } | null>(null)

  // Live tail state
  const [liveTail, setLiveTail] = useState(false)

  // ---- Atomic filter updater -----------------------------------------------
  const setFilters = useCallback(
    (overrides: Partial<Record<'serviceId' | 'level' | 'text' | 'timeRange' | 'from' | 'to', string>>) => {
      setParams((prev) => {
        const next = new URLSearchParams(prev)
        for (const [k, v] of Object.entries(overrides)) {
          if (v && v !== '_all') {
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

  // ---- Debounce keyword input ----------------------------------------------
  // Restarts whenever the URL changes too: a timer kept from an older render would write the text
  // over the URL as it was then, and drop a level or service picked while it waited (as in #218).
  useEffect(() => {
    if (inputText === textParam) return
    const timer = setTimeout(() => setFilters({ text: inputText }), 400)
    return () => clearTimeout(timer)
  }, [inputText, textParam, setFilters])

  // ---- Time window (always bounded, no "All time") ---------------------------
  // A chosen range becomes `from` when each request is sent, so live tail keeps asking for the
  // last N minutes. Worked out once, the window would grow while the page stays open, and pass
  // #156's 24-hour cap after a day. Unknown ranges (stale URLs) default to 1 hour.
  const rangeMinutes = TIME_RANGES.find((r) => r.id === timeRangeParam)?.minutes ?? 60

  // ---- Service list for dropdown -------------------------------------------
  const services = useQuery({
    queryKey: ['services-choices'],
    queryFn: listServiceChoices,
  })

  // ---- Build query object --------------------------------------------------
  // Field filter uses ?field=key:value (Loki label filter syntax proposed on #156).
  // Text filter and field filter are independent query params. `from` is left out for a chosen
  // range; withWindow adds it at request time.
  const request = useMemo(
    () => ({
      params: {
        serviceId: serviceId || undefined,
        level: level || undefined,
        text: textParam || undefined,
        field: fieldFilter ? `${fieldFilter.key}:${fieldFilter.value}` : undefined,
        from: explicitFrom || undefined,
        to: explicitFrom ? explicitTo || undefined : undefined,
        limit: 100,
      } satisfies LogsQueryParams,
      rangeMinutes,
    }),
    [serviceId, level, textParam, fieldFilter, explicitFrom, explicitTo, rangeMinutes],
  )

  // ---- Logs query with live tail polling (min 5 s to respect Loki limits) ---
  // Polling is cancelled automatically on unmount (react-query handles this via
  // the queryClient being torn down); we also cancel explicitly on filter change
  // by keying off the request.
  const logsQuery = useQuery({
    queryKey: ['telemetry-logs', request],
    queryFn: () => fetchLogs(withWindow(request.params, request.rangeMinutes)),
    refetchInterval: liveTail ? LIVE_TAIL_INTERVAL_MS : false,
    placeholderData: (prev) => prev,
  })

  // Stop live tail whenever query params change to avoid stale concurrent polls.
  const prevRequestRef = useRef(request)
  useEffect(() => {
    if (prevRequestRef.current !== request) {
      prevRequestRef.current = request
      setLiveTail(false)
    }
  }, [request])

  // ---- Clickable JSON field handler ----------------------------------------
  // Recognized structural fields (level, serviceId) are routed to their
  // dedicated filters. All other fields are sent as ?field=key:value (Loki
  // label filter syntax proposed to Dhruva on issue #156).
  const handleJsonFieldClick = (key: string, val: string) => {
    const kLower = key.toLowerCase()
    if (kLower === 'level') {
      const upper = val.toUpperCase()
      if (LOG_LEVELS.includes(upper as (typeof LOG_LEVELS)[number])) {
        setFilters({ level: upper })
        return
      }
    }
    if (kLower === 'serviceid' || kLower === 'service_id') {
      setFilters({ serviceId: val })
      return
    }
    // General attribute: set field filter chip — query sends ?field=key:val
    setFieldFilter({ key, value: val })
  }

  const clearFieldFilter = () => {
    setFieldFilter(null)
  }

  // ---- Clear all filters ---------------------------------------------------
  const hasActiveFilters = Boolean(
    serviceId || level || textParam || (timeRangeParam && timeRangeParam !== '1h') || fieldFilter || explicitFrom,
  )

  const clearAllFilters = () => {
    setInputText('')
    setFieldFilter(null)
    setParams({})
  }

  // ---- Accessibility IDs ---------------------------------------------------
  const serviceIdAttr = useId()
  const levelIdAttr = useId()
  const timeRangeIdAttr = useId()
  const textIdAttr = useId()

  // ---- Error detection: 503 only via ApiRequestError.status ----------------
  // Never match by error message string — that would trigger on any error whose
  // message happens to contain "503" or "unavailable".
  const is503 =
    logsQuery.isError &&
    logsQuery.error instanceof ApiRequestError &&
    logsQuery.error.status === 503

  return (
    <div className="mx-auto flex max-w-[1240px] flex-col gap-6">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Logs Explorer
          </h1>
          <p className="text-muted-foreground">
            Search and live tail logs across your services. Times are IST.
          </p>
        </div>

        {/* Live tail toggle */}
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant={liveTail ? 'default' : 'outline'}
            size="sm"
            onClick={() => setLiveTail((prev) => !prev)}
            aria-pressed={liveTail}
            aria-label="Live tail"
            className="gap-2 font-medium"
          >
            <span
              className={cn(
                'size-2 rounded-full transition-colors',
                liveTail ? 'bg-emerald-400 animate-pulse' : 'bg-muted-foreground',
              )}
              aria-hidden="true"
            />
            {liveTail ? 'Live tail on' : 'Live tail'}
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => logsQuery.refetch()}
            disabled={logsQuery.isFetching}
            aria-label="Refresh logs"
            title="Refresh logs"
          >
            <RefreshCw className={cn('size-4', logsQuery.isFetching && 'animate-spin')} aria-hidden="true" />
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
            <Select
              value={serviceId}
              onValueChange={(val) => setFilters({ serviceId: val === '_all' ? '' : val })}
            >
              <SelectTrigger id={serviceIdAttr} aria-label="Service" className="w-full">
                <SelectValue placeholder="All services" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="_all">All services</SelectItem>
                {services.data?.map((svc) => (
                  <SelectItem key={svc.id} value={String(svc.id)}>
                    {svc.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Level filter */}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={levelIdAttr} className="text-sm font-medium">
              Level
            </Label>
            <Select
              value={level}
              onValueChange={(val) => setFilters({ level: val === '_all' ? '' : val })}
            >
              <SelectTrigger id={levelIdAttr} aria-label="Level" className="w-full">
                <SelectValue placeholder="All levels" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="_all">All levels</SelectItem>
                {LOG_LEVELS.map((lvl) => (
                  <SelectItem key={lvl} value={lvl}>
                    {lvl}
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

          {/* Text search */}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={textIdAttr} className="text-sm font-medium">
              Search text
            </Label>
            <div className="relative">
              <Search
                className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                id={textIdAttr}
                type="search"
                placeholder="Search text or pattern…"
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                className="pl-9"
                aria-label="Search logs"
              />
            </div>
          </div>
        </div>

        {/* Active field filter and clear action */}
        {(hasActiveFilters || fieldFilter) && (
          <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3">
            <div className="flex flex-wrap items-center gap-2">
              {fieldFilter && (
                <Badge variant="secondary" className="gap-1.5 px-2 py-0.5 text-xs font-normal">
                  <span>
                    Field: <strong className="font-mono">{fieldFilter.key}</strong> ={' '}
                    <span className="font-mono">{fieldFilter.value}</span>
                  </span>
                  <button
                    type="button"
                    onClick={clearFieldFilter}
                    aria-label="Clear field filter"
                    className="rounded-xs opacity-70 hover:opacity-100"
                  >
                    <X className="size-3" aria-hidden="true" />
                  </button>
                </Badge>
              )}
            </div>

            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={clearAllFilters}
              className="gap-1.5 text-xs text-muted-foreground hover:text-foreground"
            >
              <X className="size-3.5" aria-hidden="true" />
              Clear filters
            </Button>
          </div>
        )}
      </div>

      {/* Main Results / States */}
      {logsQuery.isPending ? (
        <LoadingState message="Loading logs…" />
      ) : is503 ? (
        /* Grafana 503 Unavailable State */
        <div
          role="alert"
          className="flex flex-col items-center gap-3 rounded-lg border bg-sev-1-bg p-8 text-center text-sev-1-fg"
        >
          <CircleAlert className="size-8" aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <p className="text-base font-semibold">Log store unavailable</p>
            <p className="text-sm">
              The backend could not reach Grafana Loki (503). The log store may be slow or temporarily down. Other Settle services remain fully operational.
            </p>
          </div>
          <Button variant="outline" size="sm" onClick={() => logsQuery.refetch()}>
            Try again
          </Button>
        </div>
      ) : logsQuery.isError ? (
        /* Generic Error State */
        <div
          role="alert"
          className="flex flex-col items-center gap-3 rounded-lg border bg-sev-1-bg p-8 text-center text-sev-1-fg"
        >
          <CircleAlert className="size-8" aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <p className="text-base font-semibold">Failed to load logs</p>
            <p className="text-sm">{logsQuery.error.message}</p>
          </div>
          <Button variant="outline" size="sm" onClick={() => logsQuery.refetch()}>
            Try again
          </Button>
        </div>
      ) : logsQuery.data?.length === 0 ? (
        /* Empty State */
        <div className="flex flex-col items-center gap-4 rounded-lg border bg-card py-20 text-center">
          <span className="grid size-12 place-items-center rounded-full bg-muted text-muted-foreground">
            <FileText className="size-5" aria-hidden="true" />
          </span>
          <div className="flex flex-col gap-1">
            <p className="font-semibold">No logs found</p>
            <p className="text-sm text-muted-foreground">
              No logs match the current filters and time range.
            </p>
          </div>
          {hasActiveFilters && (
            <Button variant="outline" size="sm" onClick={clearAllFilters}>
              Clear filters
            </Button>
          )}
        </div>
      ) : (
        /* Log List */
        <div className="flex flex-col gap-3">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>{logsQuery.data.length} log entries</span>
            {liveTail && <span className="text-emerald-500 font-medium">● Polling active</span>}
          </div>

          <div className="overflow-hidden rounded-lg border bg-card font-mono text-xs">
            <div className="divide-y divide-border">
              {logsQuery.data.map((log) => {
                const fields = extractJsonFields(log)
                return (
                  <div
                    key={log.id}
                    data-testid="log-entry"
                    className="group flex flex-col gap-1.5 p-3 hover:bg-muted/40 transition-colors"
                  >
                    {/* Header line: Timestamp, Level, Service */}
                    <div className="flex flex-wrap items-center gap-2 text-muted-foreground">
                      <span className="text-[11px] tnum">{formatIst(log.timestamp)}</span>
                      <LevelBadge level={log.level} />
                      {log.service && (
                        <span className="rounded bg-muted px-1.5 py-0.5 text-[10.5px] font-sans font-medium text-foreground">
                          {log.service}
                        </span>
                      )}
                    </div>

                    {/* Log message */}
                    <div className="font-mono text-xs text-foreground/90 break-all select-text">
                      {log.message}
                    </div>

                    {/* Clickable structured JSON fields */}
                    {fields && (
                      <div className="mt-1 flex flex-wrap gap-1.5 pt-0.5">
                        {Object.entries(fields).map(([k, v]) => {
                          const valStr = typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)
                          return (
                            <button
                              key={k}
                              type="button"
                              aria-label={`Filter by ${k}: ${valStr}`}
                              onClick={() => handleJsonFieldClick(k, valStr)}
                              className="inline-flex items-center gap-1 rounded border border-border bg-muted/40 px-1.5 py-0.5 text-[11px] text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                            >
                              <span className="font-semibold text-foreground/75">{k}:</span>
                              <span>{valStr}</span>
                            </button>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

