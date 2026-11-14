import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { listAlerts, type Alert, type AlertStatus } from '@/api/alerts'
import { useAuth } from '@/auth/AuthProvider'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatIst, istDayStart } from '@/lib/time'
import { cn } from '@/lib/utils'

// NEW: the alert opened its incident. ATTACHED: it joined one already open (V8__alert_intake.sql).
const FILTERS = [
  { id: 'all', label: 'All', matches: () => true, none: 'No alerts yet. Tools send them to /webhooks/alerts.' },
  { id: 'new', label: 'Opened', matches: (status: AlertStatus) => status === 'NEW', none: 'No alert opened an incident.' },
  { id: 'attached', label: 'Grouped', matches: (status: AlertStatus) => status === 'ATTACHED', none: 'No alert joined an open incident.' },
] as const

const HOW: Record<AlertStatus, string> = { NEW: 'Opened the incident', ATTACHED: 'Grouped' }

const listOf = (words: string[]) =>
  words.length < 2 ? (words[0] ?? '') : `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`

function Tile({ label, value, detail }: { label: string; value: number; detail: string }) {
  return (
    <div className="flex flex-col gap-3 px-5 py-5">
      <span className="text-[12px] text-muted-foreground">{label}</span>
      <span className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
        <strong className="font-mono text-[30px] leading-none font-medium tnum">{value}</strong>
        <span className="text-[12px] text-muted-foreground">{detail}</span>
      </span>
    </div>
  )
}

/** Today's alerts in three numbers: all of them, the ones that opened an incident, and the ones grouped into one. */
function Today({ alerts }: { alerts: Alert[] }) {
  const today = alerts.filter((alert) => Date.parse(alert.createdAt) >= istDayStart(Date.now()))
  const sources = [...new Set(today.map((alert) => alert.source))].toSorted()
  return (
    <section
      aria-label="Alerts today"
      className="mb-6 grid divide-y rounded-lg border bg-card shadow-elev-1 md:grid-cols-3 md:divide-x md:divide-y-0"
    >
      <Tile label="Received today" value={today.length} detail={sources.length > 0 ? `from ${listOf(sources)}` : 'none yet'} />
      <Tile
        label="Opened an incident"
        value={today.filter((alert) => alert.status === 'NEW').length}
        detail="the first alert for its correlation key"
      />
      <Tile label="Grouped" value={today.filter((alert) => alert.status === 'ATTACHED').length} detail="joined an incident already open" />
    </section>
  )
}

/** Alerts from tools and monitors, newest first, with the incident each one opened or joined. */
export function AlertsPage() {
  const { user } = useAuth()
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: listAlerts })
  // The filters live in the URL, so a link opens the same view.
  const [params, setParams] = useSearchParams()
  const filter = FILTERS.find((candidate) => candidate.id === params.get('status')) ?? FILTERS[0]
  // Routed to me: the alert's incident is assigned to the viewer. It narrows whichever status is chosen.
  const routedToMe = params.get('routed') === 'me'
  const inScope = alerts.data?.filter((alert) => !routedToMe || alert.assigneeId === user?.id)
  const shown = inScope
    ?.filter((alert) => filter.matches(alert.status))
    .toSorted((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))

  function showStatus(id: string) {
    const next = new URLSearchParams(params)
    if (id === 'all') next.delete('status')
    else next.set('status', id)
    setParams(next)
  }

  function toggleRoutedToMe() {
    const next = new URLSearchParams(params)
    if (routedToMe) next.delete('routed')
    else next.set('routed', 'me')
    setParams(next)
  }

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader title="Alert inbox" description="Every alert received, and the incident it opened or joined. Times in IST." />

      {alerts.data && <Today alerts={alerts.data} />}

      <div className="mb-5 flex flex-wrap items-end justify-between gap-3 border-b">
        <div role="group" aria-label="Show alerts" className="flex gap-5">
          {FILTERS.map((option) => (
            <button
              key={option.id}
              type="button"
              aria-pressed={option.id === filter.id}
              onClick={() => showStatus(option.id)}
              className={cn(
                '-mb-px flex items-center gap-2 border-b-2 border-transparent pb-3 text-[13px] text-muted-foreground transition-colors outline-none hover:text-foreground focus-visible:text-foreground',
                option.id === filter.id && 'border-primary font-medium text-foreground',
              )}
            >
              {option.label}
              {inScope && (
                <span className="rounded-[5px] bg-secondary px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground tnum">
                  {inScope.filter((alert) => option.matches(alert.status)).length}
                </span>
              )}
            </button>
          ))}
        </div>
        <button
          type="button"
          aria-pressed={routedToMe}
          onClick={toggleRoutedToMe}
          className={cn(
            'mb-2 rounded-md border bg-card px-3 py-1.5 text-[13px] text-muted-foreground transition-colors hover:text-foreground',
            routedToMe && 'border-primary text-foreground',
          )}
        >
          Routed to me
        </button>
      </div>

      {alerts.isPending ? (
        <LoadingState message="Loading alerts" />
      ) : alerts.isError ? (
        <ErrorState message={alerts.error.message} onRetry={() => alerts.refetch()} />
      ) : shown?.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          {routedToMe ? (filter.id === 'all' ? 'No alerts routed to you.' : `${filter.none.slice(0, -1)} among those routed to you.`) : filter.none}
        </p>
      ) : (
        <Panel>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-[19px]">Alert</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead>How it arrived</TableHead>
                  <TableHead>Incident</TableHead>
                  <TableHead className="text-right">Alerts</TableHead>
                  <TableHead className="pr-[19px]">Received</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {shown?.map((alert) => (
                  <TableRow key={alert.id}>
                    <TableCell className="pl-[19px]">
                      <span className="flex flex-col gap-1">
                        <span className="font-medium">{alert.serviceName}</span>
                        <span className="font-mono text-[12px] text-muted-foreground">
                          {alert.correlationKey ? `key ${alert.correlationKey}` : 'no correlation key'}
                        </span>
                      </span>
                    </TableCell>
                    <TableCell>{alert.source}</TableCell>
                    <TableCell className={cn(alert.status === 'NEW' && 'font-medium')}>{HOW[alert.status]}</TableCell>
                    <TableCell>
                      <Link to={`/incidents/${alert.incidentId}`} className="font-mono text-[13px] hover:underline">
                        {alert.incidentReference}
                      </Link>
                    </TableCell>
                    <TableCell
                      className="text-right font-mono text-[13px] tnum"
                      title={`${alert.count} ${alert.count === 1 ? 'alert' : 'alerts'} grouped into ${alert.incidentReference}`}
                    >
                      {alert.count}
                    </TableCell>
                    <TableCell className="pr-[19px] font-mono text-[12px] whitespace-nowrap text-muted-foreground tnum">
                      {formatIst(alert.createdAt)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <PanelFooter>
            <span>{shown?.length === 1 ? '1 alert' : `${shown?.length} alerts`}</span>
            <span>All times in IST</span>
          </PanelFooter>
        </Panel>
      )}
    </div>
  )
}
