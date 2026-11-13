import { useQuery } from '@tanstack/react-query'
import { Columns3, Eye, Search, Table2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { listIncidents, type IncidentSummary } from '@/api/incidents'
import { incidentStatusSchema, severitySchema } from '@/api/schemas'
import { useAuth } from '@/auth/AuthProvider'
import { Avatar } from '@/components/app/Avatar'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel } from '@/components/app/Panel'
import { SlaBadge } from '@/components/app/SlaClock'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { runningClock } from '@/lib/sla'
import { formatIst } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { cn } from '@/lib/utils'
import { IncidentPeek } from './incidents/IncidentPeek'
import { applyFilters, BOARD_COLUMNS, inScope, readFilters, SCOPES, withFilter, type IncidentFilters } from './incidents/filters'

const newest = (a: IncidentSummary, b: IncidentSummary) => Date.parse(b.createdAt) - Date.parse(a.createdAt)

// Time left on the clock that still runs; overdue is negative, so it comes first. Stopped clocks go last.
const timeLeft = (incident: IncidentSummary) => {
  const clock = runningClock(incident)
  return clock === null ? Number.POSITIVE_INFINITY : Date.parse(clock.dueAt)
}

const SORTS = [
  { id: 'newest', label: 'Newest', compare: newest },
  // SEV1 first; within a severity, newest first.
  { id: 'severity', label: 'Severity', compare: (a: IncidentSummary, b: IncidentSummary) => a.severity.localeCompare(b.severity) || newest(a, b) },
  // Comparing due times is comparing time left, since every clock counts down from the same now.
  { id: 'time-left', label: 'Time left', compare: (a: IncidentSummary, b: IncidentSummary) => timeLeft(a) - timeLeft(b) || newest(a, b) },
] as const

const VIEWS = [
  { id: 'table', label: 'Table', icon: Table2 },
  { id: 'board', label: 'Board', icon: Columns3 },
] as const

// Radix Select has no empty value, so "everything" is a value of its own here and '' in the URL.
const ALL = 'all'

function TimeLeft({ incident, now }: { incident: IncidentSummary; now: number }) {
  const clock = runningClock(incident)
  if (clock === null) return <span className="text-xs text-muted-foreground">Clocks stopped</span>
  return <SlaBadge name={clock.name} dueAt={clock.dueAt} now={now} />
}

function Lead({ incident, compact }: { incident: IncidentSummary; compact?: boolean }) {
  if (!incident.assignee) return <span className="text-[12px] text-muted-foreground">No Lead yet</span>
  const name = incident.assignee.displayName
  return (
    <span className="flex items-center gap-2 text-[12px] whitespace-nowrap">
      <Avatar name={name} size="sm" />
      {compact ? name.split(' ')[0] : name}
    </span>
  )
}

function PeekButton({ incident, onPeek }: { incident: IncidentSummary; onPeek: (incident: IncidentSummary) => void }) {
  return (
    <Button variant="ghost" size="icon-sm" aria-label={`Preview ${incident.reference}`} onClick={() => onPeek(incident)}>
      <Eye aria-hidden="true" />
    </Button>
  )
}

function FilterSelect({
  label,
  value,
  onChange,
  everything,
  options,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  everything: string
  options: { value: string; label: string }[]
}) {
  return (
    <Select value={value || ALL} onValueChange={(next) => onChange(next === ALL ? '' : next)}>
      <SelectTrigger aria-label={label} className="h-[38px] w-full bg-card sm:w-[170px]">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL}>{everything}</SelectItem>
        {options.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function IncidentTable({ incidents, now, onPeek }: { incidents: IncidentSummary[]; now: number; onPeek: (incident: IncidentSummary) => void }) {
  return (
    <Panel>
      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-[100px] pl-[19px]">Reference</TableHead>
              <TableHead>Incident</TableHead>
              <TableHead className="w-[90px]">Severity</TableHead>
              <TableHead className="w-[130px]">Status</TableHead>
              <TableHead className="w-[230px]">Time left</TableHead>
              <TableHead className="w-[150px]">Lead</TableHead>
              <TableHead className="w-[140px]">Declared</TableHead>
              <TableHead className="w-[52px]">
                <span className="sr-only">Preview</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {incidents.map((incident) => (
              <TableRow key={incident.id}>
                <TableCell className="pl-[19px] font-mono text-xs text-muted-foreground">{incident.reference}</TableCell>
                <TableCell>
                  <span className="flex flex-col gap-0.5">
                    <Link to={`/incidents/${incident.id}`} className="font-medium hover:underline">
                      {incident.title}
                    </Link>
                    <span className="text-[12px] text-muted-foreground">{incident.serviceName}</span>
                  </span>
                </TableCell>
                <TableCell>
                  <SeverityBadge severity={incident.severity} />
                </TableCell>
                <TableCell>
                  <StatusBadge status={incident.status} />
                </TableCell>
                <TableCell>
                  <TimeLeft incident={incident} now={now} />
                </TableCell>
                <TableCell>
                  <Lead incident={incident} />
                </TableCell>
                <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(incident.createdAt)}</TableCell>
                <TableCell>
                  <PeekButton incident={incident} onPeek={onPeek} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <div className="flex justify-between border-t px-[19px] py-3.5 text-[12px] text-muted-foreground">
        <span>
          {incidents.length} {incidents.length === 1 ? 'incident' : 'incidents'}
        </span>
        <span>All times in IST</span>
      </div>
    </Panel>
  )
}

function IncidentBoard({ incidents, now, onPeek }: { incidents: IncidentSummary[]; now: number; onPeek: (incident: IncidentSummary) => void }) {
  return (
    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
      {BOARD_COLUMNS.map((column) => {
        const cards = incidents.filter((incident) => column.statuses.includes(incident.status))
        return (
          <section
            key={column.id}
            aria-labelledby={`board-${column.id}`}
            className="flex min-w-0 flex-col gap-3 rounded-lg border bg-card/50 p-3"
          >
            <h2 id={`board-${column.id}`} className="flex items-center justify-between px-1 pt-1 text-[13px] font-medium">
              {column.label}
              <span className="font-mono text-[12px] text-muted-foreground">{cards.length}</span>
            </h2>
            {cards.length === 0 ? (
              <p className="rounded-md border border-dashed p-5 text-center text-[12px] text-muted-foreground">No incidents here</p>
            ) : (
              cards.map((incident) => (
                <article
                  key={incident.id}
                  aria-label={`${incident.reference} ${incident.title}`}
                  className="flex flex-col gap-2.5 rounded-lg border bg-card p-3.5 shadow-elev-1"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-[12px] text-muted-foreground">{incident.reference}</span>
                    <SeverityBadge severity={incident.severity} />
                  </div>
                  <Link to={`/incidents/${incident.id}`} className="text-[13px] leading-snug font-medium hover:underline">
                    {incident.title}
                  </Link>
                  <span className="text-[12px] text-muted-foreground">{incident.serviceName}</span>
                  <StatusBadge status={incident.status} />
                  <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-2.5">
                    <Lead incident={incident} compact />
                    <TimeLeft incident={incident} now={now} />
                  </div>
                  <Button variant="outline" size="sm" className="self-start" onClick={() => onPeek(incident)}>
                    <Eye aria-hidden="true" />
                    Preview <span className="sr-only">{incident.reference}</span>
                  </Button>
                </article>
              ))
            )}
          </section>
        )
      })}
    </div>
  )
}

/**
 * Every incident, as a table or a status board. Scope, search, the three filters, the sort, and the view all
 * live in the URL, so switching view, reloading, or sharing the link keeps them. Viewers read; Engineers and
 * Admins declare from the top bar.
 */
export function IncidentsPage() {
  const { user } = useAuth()
  const now = useNow()
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  const [params, setParams] = useSearchParams()
  const [peek, setPeek] = useState<IncidentSummary | null>(null)
  const sort = SORTS.find((candidate) => candidate.id === params.get('sort')) ?? SORTS[0]
  const view = params.get('view') === 'board' ? 'board' : 'table'
  const filters = readFilters(params)
  const setFilter = (key: keyof IncidentFilters, value: string) => setParams(withFilter(params, key, value), { replace: true })
  const setParam = (key: string, value: string | null) => {
    const next = new URLSearchParams(params)
    if (value === null) next.delete(key)
    else next.set(key, value)
    setParams(next)
  }

  const all = incidents.data ?? []
  const shown = applyFilters(all, filters, user?.id).toSorted(sort.compare)
  const services = [...new Set(all.map((incident) => incident.serviceName))].sort()

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Incidents"
        description="Active and past incidents, with the SLA clock that still runs. Times are IST. Declare from the top bar or with C."
      />

      <div className="mb-5 flex flex-wrap items-end justify-between gap-3 border-b">
        <div role="group" aria-label="Show" className="-mb-px flex gap-1 overflow-x-auto">
          {SCOPES.map((scope) => (
            <button
              key={scope.id}
              type="button"
              aria-pressed={filters.scope === scope.id}
              onClick={() => setFilter('scope', scope.id)}
              className={cn(
                'flex items-center gap-2 border-b-2 border-transparent px-2.5 pb-3 text-[13px] whitespace-nowrap text-muted-foreground transition-colors hover:text-foreground',
                filters.scope === scope.id && 'border-primary font-medium text-foreground',
              )}
            >
              {scope.label}
              <span className="rounded-[4px] bg-muted px-1.5 font-mono text-[11px] tnum">
                {all.filter((incident) => inScope(incident, scope.id, user?.id)).length}
              </span>
            </button>
          ))}
        </div>
        <div role="group" aria-label="View" className="mb-2 flex rounded-lg border bg-card p-1">
          {VIEWS.map((option) => (
            <button
              key={option.id}
              type="button"
              aria-pressed={view === option.id}
              onClick={() => setParam('view', option.id === 'table' ? null : option.id)}
              className={cn(
                'flex items-center gap-1.5 rounded-md px-2.5 py-1 text-[13px] text-muted-foreground transition-colors hover:text-foreground',
                view === option.id && 'bg-accent text-foreground',
              )}
            >
              <option.icon className="size-4" aria-hidden="true" />
              {option.label}
            </button>
          ))}
        </div>
      </div>

      <div className="mb-5 flex flex-wrap items-center gap-2.5">
        <div className="relative w-full sm:w-[220px]">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <Input
            id="q"
            type="search"
            aria-label="Search incidents"
            placeholder="Search incidents…"
            value={filters.q}
            onChange={(event) => setFilter('q', event.target.value)}
            className="h-[38px] bg-card pl-9"
          />
        </div>
        <FilterSelect
          label="Filter service"
          value={filters.service}
          onChange={(value) => setFilter('service', value)}
          everything="All services"
          options={services.map((name) => ({ value: name, label: name }))}
        />
        <FilterSelect
          label="Filter severity"
          value={filters.severity}
          onChange={(value) => setFilter('severity', value)}
          everything="All severities"
          options={severitySchema.options.map((severity) => ({ value: severity, label: severity }))}
        />
        <FilterSelect
          label="Filter status"
          value={filters.status}
          onChange={(value) => setFilter('status', value)}
          everything="All statuses"
          options={incidentStatusSchema.options.map((status) => ({ value: status, label: status.charAt(0) + status.slice(1).toLowerCase() }))}
        />
        <div role="group" aria-label="Sort incidents" className="flex items-center gap-1 rounded-lg border bg-card p-1 sm:ml-auto">
          <span className="px-2 text-xs font-medium text-muted-foreground">Sort by</span>
          {SORTS.map((option) => (
            <button
              key={option.id}
              type="button"
              aria-pressed={option.id === sort.id}
              onClick={() => setParam('sort', option.id === 'newest' ? null : option.id)}
              className={cn(
                'rounded-md px-2.5 py-1 text-[13px] text-muted-foreground transition-colors hover:text-foreground',
                option.id === sort.id && 'bg-accent text-foreground',
              )}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      {incidents.isPending ? (
        <LoadingState message="Loading incidents" />
      ) : incidents.isError ? (
        <ErrorState message={incidents.error.message} onRetry={() => incidents.refetch()} />
      ) : all.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">No incidents yet.</p>
      ) : shown.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">No incidents match these filters.</p>
      ) : view === 'board' ? (
        <IncidentBoard incidents={shown} now={now} onPeek={setPeek} />
      ) : (
        <IncidentTable incidents={shown} now={now} onPeek={setPeek} />
      )}

      <IncidentPeek incident={peek} onClose={() => setPeek(null)} />
    </div>
  )
}
