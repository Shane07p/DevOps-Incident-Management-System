import { useQuery } from '@tanstack/react-query'
import { History, Search, X } from 'lucide-react'
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { type HistorySearchParams, searchHistory } from '@/api/history'
import type { MatchLocation } from '@/api/history'
import { listServiceChoices } from '@/api/incidents'
import { incidentStatusSchema, severitySchema } from '@/api/schemas'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
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

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SEVERITIES = severitySchema.options
const STATUSES = incidentStatusSchema.options
const PAGE_SIZE = 20

const MATCH_LABEL: Record<MatchLocation, string> = {
  title: 'Title',
  description: 'Description',
  timeline: 'Timeline',
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Read a URL param; return undefined if absent or empty. */
function param(params: URLSearchParams, key: string): string | undefined {
  const v = params.get(key)
  return v && v.trim() ? v.trim() : undefined
}

function pageParam(params: URLSearchParams): number {
  const raw = params.get('page')
  const n = raw ? parseInt(raw, 10) : 1
  return Number.isFinite(n) && n > 0 ? n : 1
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function MatchBadges({ matched }: { matched: MatchLocation[] }) {
  return (
    <span className="flex flex-wrap gap-1">
      {matched.map((loc) => (
        <Badge key={loc} variant="outline" className="text-[10px] font-normal capitalize text-muted-foreground">
          {MATCH_LABEL[loc]}
        </Badge>
      ))}
    </span>
  )
}

function PaginationBar({
  page,
  totalElements,
  totalPages,
  onPage,
}: {
  page: number
  totalElements: number
  totalPages: number
  onPage: (p: number) => void
}) {
  if (totalPages <= 1) return null
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-4 pt-2">
      <span className="text-sm text-muted-foreground">
        Page {page} of {totalPages} ({totalElements} results)
      </span>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </Button>
        <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => onPage(page + 1)}>
          Next
        </Button>
      </div>
    </nav>
  )
}

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

/**
 * History Search Page (US-15 / Issue #168).
 * All filter state lives in the URL so links, reload, and browser back/forward
 * all restore the same view.
 *
 * API dependency: GET /api/incidents/search (backend issue #158).
 */
export function HistorySearchPage() {
  const [params, setParams] = useSearchParams()

  // ---- derive filter state from URL -------------------------------------------
  const q = param(params, 'q') ?? ''
  const serviceId = param(params, 'serviceId') ?? ''
  const severity = param(params, 'severity') ?? ''
  const status = param(params, 'status') ?? ''
  const page = pageParam(params)

  // ---- controlled search input (local only; pushed to URL on submit/debounce) ---
  const [inputValue, setInputValue] = useState(q)
  // Keep local input in sync when back/forward changes the URL param.
  const prevQ = useRef(q)
  if (prevQ.current !== q) {
    prevQ.current = q
    setInputValue(q)
  }

  // ---- push all filter state atomically into the URL --------------------------
  const setFilters = useCallback(
    (overrides: Partial<Record<'q' | 'serviceId' | 'severity' | 'status' | 'page', string>>) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          for (const [key, value] of Object.entries(overrides)) {
            if (value) {
              next.set(key, value)
            } else {
              next.delete(key)
            }
          }
          // Reset page when any filter changes (except when explicitly setting page)
          if (!('page' in overrides)) next.delete('page')
          return next
        },
        { replace: false },
      )
    },
    [setParams],
  )

  // ---- debounced keyword search ------------------------------------------------
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      if (inputValue !== q) {
        setFilters({ q: inputValue })
      }
    }, 400)
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inputValue])

  // ---- service list for filter dropdown ----------------------------------------
  const services = useQuery({
    queryKey: ['services-choices'],
    queryFn: listServiceChoices,
  })

  // ---- build query params object -----------------------------------------------
  // UI page is 1-based; backend PR #209 expects 0-based page number (Shane's review fix 2)
  const backendPage = Math.max(0, page - 1)
  const searchParams: HistorySearchParams = {
    q: q || undefined,
    serviceId: serviceId || undefined,
    severity: severity || undefined,
    status: status || undefined,
    page: backendPage,
  }

  // ---- main data query  -------------------------------------------------------
  const hasFilters = !!(q || serviceId || severity || status)
  const results = useQuery({
    queryKey: ['history-search', searchParams],
    queryFn: () => searchHistory(searchParams),
    enabled: true, // always fetch (shows full history with no filters)
    placeholderData: (prev) => prev,
  })

  // ---- clear all filters -------------------------------------------------------
  const clearFilters = () => {
    setInputValue('')
    setParams({}, { replace: false })
  }

  // ---- accessibility ids -------------------------------------------------------
  const searchId = useId()
  const serviceId_ = useId()
  const severityId = useId()
  const statusId = useId()

  // ---- render -----------------------------------------------------------------
  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      {/* Page heading */}
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
          History
        </h1>
        <p className="text-muted-foreground">
          Search past incidents by keyword, service, severity, or status. Times are IST.
        </p>
      </div>

      {/* Search + filters bar */}
      <div className="flex flex-col gap-3 rounded-lg border bg-card p-4">
        {/* Keyword search */}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={searchId} className="text-sm font-medium">
            Search
          </Label>
          <div className="relative">
            <Search
              className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              id={searchId}
              type="search"
              placeholder="Search titles, descriptions, and timeline…"
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              className="pl-9"
              aria-label="Search incidents"
            />
          </div>
        </div>

        {/* Filter row */}
        <div className="flex flex-wrap items-end gap-3">
          {/* Service filter */}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={serviceId_} className="text-sm font-medium">
              Service
            </Label>
            <Select
              value={serviceId}
              onValueChange={(v) => setFilters({ serviceId: v === '_all' ? '' : v })}
            >
              <SelectTrigger id={serviceId_} aria-label="Service" className="w-[180px]">
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

          {/* Severity filter */}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={severityId} className="text-sm font-medium">
              Severity
            </Label>
            <Select
              value={severity}
              onValueChange={(v) => setFilters({ severity: v === '_all' ? '' : v })}
            >
              <SelectTrigger id={severityId} aria-label="Severity" className="w-[140px]">
                <SelectValue placeholder="Any severity" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="_all">Any severity</SelectItem>
                {SEVERITIES.map((s) => (
                  <SelectItem key={s} value={s}>
                    {s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Status filter */}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={statusId} className="text-sm font-medium">
              Status
            </Label>
            <Select
              value={status}
              onValueChange={(v) => setFilters({ status: v === '_all' ? '' : v })}
            >
              <SelectTrigger id={statusId} aria-label="Status" className="w-[160px]">
                <SelectValue placeholder="Any status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="_all">Any status</SelectItem>
                {STATUSES.map((s) => (
                  <SelectItem key={s} value={s}>
                    {s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Clear filters */}
          {hasFilters && (
            <Button variant="ghost" size="sm" onClick={clearFilters} className="mb-0.5 gap-1.5">
              <X className="size-3.5" aria-hidden="true" />
              Clear filters
            </Button>
          )}
        </div>
      </div>

      {/* Results area */}
      {results.isPending ? (
        <LoadingState message="Searching incidents…" />
      ) : results.isError ? (
        <ErrorState
          message={results.error.message}
          onRetry={() => results.refetch()}
        />
      ) : results.data?.content.length === 0 ? (
        /* Empty state */
        <div className="flex flex-col items-center gap-4 rounded-lg border bg-card py-20 text-center">
          <span className="grid size-12 place-items-center rounded-full bg-muted text-muted-foreground">
            <History className="size-5" aria-hidden="true" />
          </span>
          <div className="flex flex-col gap-1">
            <p className="font-semibold">No matching incidents</p>
            <p className="text-sm text-muted-foreground">
              {hasFilters
                ? 'No historical incidents match your search and filters.'
                : 'No incidents have been recorded yet.'}
            </p>
          </div>
          {hasFilters && (
            <Button variant="outline" size="sm" onClick={clearFilters}>
              Clear search and filters
            </Button>
          )}
        </div>
      ) : (
        <>
          {/* Result count */}
          <div className="flex items-center justify-between gap-2">
            <p className="text-sm text-muted-foreground" aria-live="polite">
              {results.data?.totalElements ?? 0} incident{(results.data?.totalElements ?? 0) !== 1 ? 's' : ''} found
              {results.isFetching && !results.isPending && (
                <span className="ml-2 text-xs opacity-60">(updating…)</span>
              )}
            </p>
          </div>

          {/* Results table */}
          <div className="rounded-lg border bg-card">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b">
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground w-[110px]">Reference</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">Title</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground hidden sm:table-cell">Service</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground w-[90px]">Severity</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground w-[150px] hidden md:table-cell">Status</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground w-[150px] hidden lg:table-cell">Declared</th>
                  {q && (
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground w-[140px] hidden md:table-cell">
                      Matched in
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {results.data?.content.map((incident) => (
                  <tr
                    key={incident.id}
                    className="border-b last:border-0 hover:bg-muted/40 transition-colors"
                  >
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground">
                      {incident.reference}
                    </td>
                    <td className="px-4 py-3">
                      <Link
                        to={`/incidents/${incident.id}`}
                        className="font-semibold hover:underline"
                      >
                        {incident.title}
                      </Link>
                    </td>
                    <td className="px-4 py-3 hidden sm:table-cell">{incident.serviceName}</td>
                    <td className="px-4 py-3">
                      <SeverityBadge severity={incident.severity} />
                    </td>
                    <td className="px-4 py-3 hidden md:table-cell">
                      <StatusBadge status={incident.status} />
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground tnum hidden lg:table-cell">
                      {formatIst(incident.createdAt)}
                    </td>
                    {q && (
                      <td className="px-4 py-3 hidden md:table-cell">
                        {incident.matchedIn && incident.matchedIn.length > 0 ? (
                          <MatchBadges matched={incident.matchedIn} />
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <PaginationBar
            page={page}
            totalElements={results.data?.totalElements ?? 0}
            totalPages={results.data?.totalPages ?? Math.ceil((results.data?.totalElements ?? 0) / PAGE_SIZE)}
            onPage={(p) => setFilters({ page: String(p) })}
          />
        </>
      )}
    </div>
  )
}

