import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { incidentReference, listAlerts, type AlertStatus } from '@/api/alerts'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { formatIst } from '@/lib/time'

const FILTERS = [
  { id: 'all', label: 'All', matches: () => true },
  { id: 'new', label: 'New', matches: (status: AlertStatus) => status === 'NEW' },
  { id: 'attached', label: 'Attached', matches: (status: AlertStatus) => status === 'ATTACHED' },
] as const

// NEW is the one that needs a look; the rest are already handled.
function AlertStatusBadge({ status }: { status: AlertStatus }) {
  return (
    <span
      className={cn(
        'inline-flex h-[22px] items-center rounded-full px-2 font-mono text-[11px] leading-none font-semibold',
        status === 'NEW' ? 'bg-status-open-bg text-status-open' : 'bg-muted text-muted-foreground',
      )}
    >
      {status}
    </span>
  )
}

/** Alerts from tools and monitors, newest first, with the incident each one belongs to. */
export function AlertsPage() {
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: listAlerts })
  // The filter lives in the URL, so a link opens the same view.
  const [params, setParams] = useSearchParams()
  const filter = FILTERS.find((candidate) => candidate.id === params.get('status')) ?? FILTERS[0]
  const shown = alerts.data
    ?.filter((alert) => filter.matches(alert.status))
    .toSorted((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
          Alerts
        </h1>
        <p className="text-muted-foreground">
          Alerts from tools and monitors, and the incidents they were grouped into. Times are IST.
        </p>
      </div>

      <div role="group" aria-label="Show alerts" className="flex w-fit gap-1 rounded-lg border bg-card p-1">
        {FILTERS.map((option) => (
          <button
            key={option.id}
            type="button"
            aria-pressed={option.id === filter.id}
            onClick={() => setParams(option.id === 'all' ? {} : { status: option.id })}
            className={cn(
              'rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground',
              option.id === filter.id && 'bg-primary text-primary-foreground hover:text-primary-foreground',
            )}
          >
            {option.label}
            {alerts.data && (
              <span className="ml-1.5 font-mono text-xs opacity-70 tnum">
                {alerts.data.filter((alert) => option.matches(alert.status)).length}
              </span>
            )}
          </button>
        ))}
      </div>

      {alerts.isPending ? (
        <LoadingState message="Loading alerts" />
      ) : alerts.isError ? (
        <ErrorState message={alerts.error.message} onRetry={() => alerts.refetch()} />
      ) : shown?.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          {filter.id === 'all' ? 'No alerts yet. Tools send them to /webhooks/alerts.' : `No ${filter.label.toLowerCase()} alerts.`}
        </p>
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[150px]">Received</TableHead>
                <TableHead>Source</TableHead>
                <TableHead>Service</TableHead>
                <TableHead>Correlation key</TableHead>
                <TableHead className="w-[110px]">Status</TableHead>
                <TableHead className="w-[100px]">Incident</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown?.map((alert) => (
                <TableRow key={alert.id}>
                  <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(alert.createdAt)}</TableCell>
                  <TableCell className="font-semibold">{alert.source}</TableCell>
                  <TableCell>{alert.serviceName}</TableCell>
                  <TableCell className="font-mono text-xs">{alert.correlationKey ?? <span className="text-muted-foreground">none</span>}</TableCell>
                  <TableCell>
                    <AlertStatusBadge status={alert.status} />
                  </TableCell>
                  <TableCell>
                    <Link to={`/incidents/${alert.incidentId}`} className="font-mono text-sm font-semibold hover:underline">
                      {incidentReference(alert.incidentId)}
                    </Link>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}
