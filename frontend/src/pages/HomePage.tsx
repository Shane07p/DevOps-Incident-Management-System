import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Check, CircleAlert, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { ApiRequestError } from '@/api/client'
import { acknowledgeIncident, listIncidents, type IncidentSummary } from '@/api/incidents'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { useIstTime } from '@/components/app/IstClock'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { SlaBadge } from '@/components/app/SlaClock'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { isBreached, slaState } from '@/lib/sla'
import { useNow } from '@/lib/useNow'
import { FINISHED } from './incidents/filters'

const dateFormat = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', year: 'numeric' })

const oldestFirst = (a: IncidentSummary, b: IncidentSummary) => Date.parse(a.createdAt) - Date.parse(b.createdAt)

/** Either clock past its target: still running and overdue, or stopped late. */
function pastATarget(incident: IncidentSummary, now: number) {
  return (
    isBreached(slaState(incident.responseDueAt, incident.acknowledgedAt, now)) ||
    isBreached(slaState(incident.resolutionDueAt, incident.resolvedAt, now))
  )
}

/**
 * Acknowledge from the overview: the caller becomes Lead and the response clock stops (#83). It sends the
 * version the list showed; if someone acknowledged first, the server answers 409, the page says so, and the
 * list is read again.
 */
function AcknowledgeButton({ incident, onConflict }: { incident: IncidentSummary; onConflict: (message: string) => void }) {
  const queryClient = useQueryClient()
  const acknowledge = useMutation({
    mutationFn: () => acknowledgeIncident(incident.id, incident.version),
    onSuccess: async (updated) => {
      toast.success(`${updated.reference} acknowledged. You are Lead, and escalation has stopped.`)
      queryClient.setQueryData(['incident', updated.id], updated)
      await queryClient.invalidateQueries({ queryKey: ['incidents'] })
    },
    onError: async (error) => {
      if (error instanceof ApiRequestError && error.status === 409) {
        onConflict(`Someone else acknowledged ${incident.reference} first, so you are not its Lead. The list is up to date again.`)
      } else {
        toast.error(error instanceof Error ? error.message : `${incident.reference} was not acknowledged.`)
      }
      await queryClient.invalidateQueries({ queryKey: ['incidents'] })
    },
  })
  return (
    <Button size="lg" onClick={() => acknowledge.mutate()} disabled={acknowledge.isPending} className="w-full sm:w-auto">
      <Check aria-hidden="true" />
      {acknowledge.isPending ? 'Acknowledging…' : 'Acknowledge and take Lead'}
      <span className="sr-only">: {incident.reference}</span>
    </Button>
  )
}

function Waiting({ incidents, now, responder }: { incidents: IncidentSummary[]; now: number; responder: boolean }) {
  const [conflict, setConflict] = useState<string | null>(null)
  return (
    <Panel
      title={
        <>
          Waiting for acknowledgement <span className="font-mono text-[12px] text-muted-foreground">{incidents.length}</span>
        </>
      }
      className="mb-6"
    >
      {conflict && (
        <div role="alert" className="mx-[19px] mb-3 flex items-start gap-2.5 rounded-md bg-sev-1-bg px-3.5 py-3 text-[13px] text-sev-1-fg">
          <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          {conflict}
        </div>
      )}
      {incidents.length === 0 ? (
        <p className="border-t px-[19px] py-6 text-[13px] text-muted-foreground">Nothing is waiting. Every open incident has a Lead.</p>
      ) : (
        <ul className="divide-y border-t">
          {incidents.map((incident) => (
            <li key={incident.id} className="flex flex-col gap-3 px-[19px] py-4 sm:flex-row sm:items-center sm:gap-5">
              <SeverityBadge severity={incident.severity} className="w-fit sm:w-[72px]" />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <Link to={`/incidents/${incident.id}`} className="text-[14px] font-medium hover:underline">
                  {incident.title}
                </Link>
                <span className="font-mono text-[12px] text-muted-foreground">
                  {incident.reference} · <span className="font-sans">{incident.serviceName}</span>
                </span>
              </div>
              <SlaBadge name="Response" dueAt={incident.responseDueAt} now={now} />
              {responder && <AcknowledgeButton incident={incident} onConflict={setConflict} />}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

function InProgress({ incidents, now }: { incidents: IncidentSummary[]; now: number }) {
  const late = incidents.filter((incident) => pastATarget(incident, now)).length
  return (
    <Panel
      title="In progress"
      action={
        <Link to="/incidents?scope=active" className="flex items-center gap-1.5 text-[12px] text-muted-foreground hover:text-foreground">
          All incidents
          <ArrowRight className="size-3.5" aria-hidden="true" />
        </Link>
      }
    >
      {incidents.length === 0 ? (
        <p className="border-t px-[19px] py-6 text-[13px] text-muted-foreground">No incident is being worked on.</p>
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="pl-[19px]">Incident</TableHead>
                <TableHead>Severity</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Clocks</TableHead>
                <TableHead>Lead</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {incidents.map((incident) => (
                <TableRow key={incident.id}>
                  <TableCell className="pl-[19px]">
                    <span className="flex flex-col gap-0.5">
                      <Link to={`/incidents/${incident.id}`} className="font-medium hover:underline">
                        {incident.title}
                      </Link>
                      <span className="font-mono text-[12px] text-muted-foreground">
                        {incident.reference} · <span className="font-sans">{incident.serviceName}</span>
                      </span>
                    </span>
                  </TableCell>
                  <TableCell>
                    <SeverityBadge severity={incident.severity} />
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={incident.status} />
                  </TableCell>
                  <TableCell>
                    <span className="flex flex-col gap-1">
                      <SlaBadge name="Response" dueAt={incident.responseDueAt} stoppedAt={incident.acknowledgedAt} now={now} />
                      <SlaBadge name="Resolution" dueAt={incident.resolutionDueAt} stoppedAt={incident.resolvedAt} now={now} />
                    </span>
                  </TableCell>
                  <TableCell>
                    {incident.assignee ? (
                      <span className="flex items-center gap-2 text-[12px] whitespace-nowrap">
                        <Avatar name={incident.assignee.displayName} size="sm" />
                        {incident.assignee.displayName}
                      </span>
                    ) : (
                      <span className="text-[12px] text-muted-foreground">No Lead yet</span>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {late > 0 && (
        <PanelFooter className="text-sla-breached-fg">
          <span className="flex items-center gap-2">
            <TriangleAlert className="size-3.5" aria-hidden="true" />
            {late === 1 ? '1 incident is past an SLA target' : `${late} incidents are past an SLA target`}
          </span>
        </PanelFooter>
      )}
    </Panel>
  )
}

/**
 * The overview (/home): what needs someone now, then what is being worked on. Engineers and Admins acknowledge
 * from here; a Viewer sees the same lists without the button.
 */
export function HomePage() {
  const { user } = useAuth()
  const { now: istNow, hhmm } = useIstTime()
  const now = useNow()
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  const all = incidents.data ?? []
  const waiting = all.filter((incident) => incident.status === 'OPEN').toSorted(oldestFirst)
  const inProgress = all.filter((incident) => incident.status !== 'OPEN' && !FINISHED.includes(incident.status)).toSorted(oldestFirst)
  const active = waiting.length + inProgress.length
  const late = [...waiting, ...inProgress].filter((incident) => pastATarget(incident, now)).length

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Overview"
        description={
          incidents.data
            ? `${active} active · ${waiting.length} waiting for acknowledgement · ${late} past an SLA target`
            : 'Your incidents and what needs someone now.'
        }
        actions={
          <span className="font-mono text-[12px] text-muted-foreground tnum">
            {dateFormat.format(istNow)} · {hhmm} IST
          </span>
        }
      />
      {incidents.isPending ? (
        <LoadingState message="Loading incidents" />
      ) : incidents.isError ? (
        <ErrorState message={incidents.error.message} onRetry={() => incidents.refetch()} />
      ) : (
        <>
          <Waiting incidents={waiting} now={now} responder={can(user?.role, 'respond')} />
          <InProgress incidents={inProgress} now={now} />
        </>
      )}
    </div>
  )
}
