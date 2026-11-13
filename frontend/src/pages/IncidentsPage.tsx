import { useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'
import { listIncidents, type IncidentSummary } from '@/api/incidents'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { SlaBadge } from '@/components/app/SlaClock'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { runningClock } from '@/lib/sla'
import { formatIst } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { cn } from '@/lib/utils'

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

function TimeLeft({ incident, now }: { incident: IncidentSummary; now: number }) {
  const clock = runningClock(incident)
  if (clock === null) return <span className="text-xs text-muted-foreground">Clocks stopped</span>
  return <SlaBadge name={clock.name} dueAt={clock.dueAt} now={now} />
}

/** Every incident, with its running SLA clock. Viewers read; Engineers and Admins also declare. */
export function IncidentsPage() {
  const { user } = useAuth()
  const now = useNow()
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  // The sort lives in the URL, so a link opens the same view.
  const [params, setParams] = useSearchParams()
  const sort = SORTS.find((candidate) => candidate.id === params.get('sort')) ?? SORTS[0]
  const sorted = incidents.data?.toSorted(sort.compare)

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Incidents
          </h1>
          <p className="text-muted-foreground">Active and past incidents, with the SLA clock that still runs. Times are IST.</p>
        </div>
        {can(user?.role, 'respond') && (
          <Button asChild>
            <Link to="/incidents/new">
              <Plus aria-hidden="true" />
              Declare incident
            </Link>
          </Button>
        )}
      </div>

      <div role="group" aria-label="Sort incidents" className="flex w-fit items-center gap-1 rounded-lg border bg-card p-1">
        <span className="px-2 text-xs font-medium text-muted-foreground">Sort by</span>
        {SORTS.map((option) => (
          <button
            key={option.id}
            type="button"
            aria-pressed={option.id === sort.id}
            onClick={() => setParams(option.id === 'newest' ? {} : { sort: option.id })}
            className={cn(
              'rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground',
              option.id === sort.id && 'bg-primary text-primary-foreground hover:text-primary-foreground',
            )}
          >
            {option.label}
          </button>
        ))}
      </div>

      {incidents.isPending ? (
        <LoadingState message="Loading incidents" />
      ) : incidents.isError ? (
        <ErrorState message={incidents.error.message} onRetry={() => incidents.refetch()} />
      ) : sorted?.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">No incidents yet.</p>
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[110px]">Reference</TableHead>
                <TableHead>Title</TableHead>
                <TableHead>Service</TableHead>
                <TableHead className="w-[90px]">Severity</TableHead>
                <TableHead className="w-[150px]">Status</TableHead>
                <TableHead className="w-[230px]">Time left</TableHead>
                <TableHead className="w-[150px]">Declared</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sorted?.map((incident) => (
                <TableRow key={incident.id}>
                  <TableCell className="font-mono text-xs text-muted-foreground">{incident.reference}</TableCell>
                  <TableCell>
                    <Link to={`/incidents/${incident.id}`} className="font-semibold hover:underline">
                      {incident.title}
                    </Link>
                  </TableCell>
                  <TableCell>{incident.serviceName}</TableCell>
                  <TableCell>
                    <SeverityBadge severity={incident.severity} />
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={incident.status} />
                  </TableCell>
                  <TableCell>
                    <TimeLeft incident={incident} now={now} />
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(incident.createdAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}
