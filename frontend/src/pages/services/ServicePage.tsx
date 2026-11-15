import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Settings } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { listIncidents, type IncidentSummary } from '@/api/incidents'
import { getOnCall } from '@/api/onCall'
import { getServiceMap } from '@/api/serviceMap'
import { listServices, SEVERITIES } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatIst, formatMinutes } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { FINISHED } from '@/pages/incidents/filters'
import { nextShift, onCallAt } from '@/pages/schedules/schedule'
import { ServiceFormDialog } from './ServiceFormDialog'
import { HEALTH, openIncidentsOf } from './serviceHealth'

function IncidentRows({ incidents }: { incidents: IncidentSummary[] }) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="pl-[19px]">Incident</TableHead>
            <TableHead>Severity</TableHead>
            <TableHead>Status</TableHead>
            <TableHead className="pr-[19px]">Declared</TableHead>
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
                  <span className="font-mono text-[12px] text-muted-foreground">{incident.reference}</span>
                </span>
              </TableCell>
              <TableCell>
                <SeverityBadge severity={incident.severity} />
              </TableCell>
              <TableCell>
                <StatusBadge status={incident.status} />
              </TableCell>
              <TableCell className="pr-[19px] font-mono text-[12px] whitespace-nowrap text-muted-foreground">
                {formatIst(incident.createdAt)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function OnCallPanel({ serviceId }: { serviceId: number }) {
  const now = useNow(60_000)
  const schedule = useQuery({ queryKey: ['on-call', serviceId], queryFn: () => getOnCall(serviceId) })
  let body: ReactNode
  if (schedule.isPending) body = <LoadingState message="Loading who is on call" />
  else if (schedule.isError) body = <p className="px-[19px] pb-5 text-[13px] text-muted-foreground">{schedule.error.message}</p>
  else {
    const current = onCallAt(schedule.data, now)
    const next = nextShift(schedule.data, now)
    body = (
      <div className="flex items-start gap-3 px-[19px] pb-5">
        {current.kind !== 'nobody' && <Avatar name={current.name} />}
        <div className="flex min-w-0 flex-col gap-1 text-[13px]">
          <strong className="font-medium">{current.kind === 'nobody' ? 'Nobody is on call' : current.name}</strong>
          <p className="text-muted-foreground">
            {current.kind === 'shift' && `Until ${formatIst(current.until)}`}
            {current.kind === 'fallback' && 'Fallback contact: no shift covers this time.'}
            {current.kind === 'nobody' && 'No shift and no fallback contact yet.'}
            {next && `${current.kind === 'shift' ? ', then' : ' Next:'} ${next.responderName} from ${formatIst(next.startTime)}.`}
            {schedule.data.fallbackContactName && current.kind === 'shift' && ` Fallback: ${schedule.data.fallbackContactName}.`}
          </p>
          <Link to={`/schedules?service=${serviceId}`} className="mt-1 flex w-fit items-center gap-1.5 text-[12px] text-muted-foreground hover:text-foreground">
            View schedule
            <ArrowRight className="size-3.5" aria-hidden="true" />
          </Link>
        </div>
      </div>
    )
  }
  return <Panel title="On call">{body}</Panel>
}

/**
 * One service: its health, owner, open and past incidents, who is on call, and its SLA targets. Admins
 * configure it from here, in the same form as the catalog.
 */
export function ServicePage() {
  const id = Number(useParams().id)
  const { user } = useAuth()
  const canConfigure = can(user?.role, 'configure')
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const map = useQuery({ queryKey: ['service-map'], queryFn: getServiceMap })
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  const [editing, setEditing] = useState(false)

  if (services.isPending) return <LoadingState message="Loading the service" />
  if (services.isError) return <ErrorState message={services.error.message} onRetry={() => services.refetch()} />
  const service = services.data.find((candidate) => candidate.id === id)
  const back = (
    <Button variant="outline" asChild>
      <Link to="/services">
        <ArrowLeft aria-hidden="true" />
        All services
      </Link>
    </Button>
  )
  if (!service) {
    return (
      <div className="mx-auto flex max-w-[1180px] flex-col">
        <PageHeader title="Service not found" description="It may have been removed, or the link is wrong." actions={back} />
      </div>
    )
  }

  const node = map.data?.nodes.find((candidate) => candidate.id === service.id)
  const state = HEALTH[node?.health ?? 'UNKNOWN']
  const open = incidents.data ? openIncidentsOf(incidents.data, service.name) : []
  const past =
    incidents.data
      ?.filter((incident) => incident.serviceName === service.name && FINISHED.includes(incident.status))
      .toSorted((a, b) => b.createdAt.localeCompare(a.createdAt)) ?? []

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-5">
      <PageHeader
        className="mb-1"
        title={service.name}
        description={service.description}
        actions={
          <>
            {back}
            {canConfigure && (
              <Button onClick={() => setEditing(true)}>
                <Settings aria-hidden="true" />
                Configure service
              </Button>
            )}
          </>
        }
      />
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-[13px]">
        {map.isSuccess && <Health tone={state.tone} className="text-[13px]">{state.label}</Health>}
        <span className="text-muted-foreground">
          <span className="capitalize">{service.criticality.toLowerCase()}</span> criticality
        </span>
        <span className="flex items-center gap-2">
          <Avatar name={service.owner.displayName} size="sm" />
          {service.owner.displayName}
          <span className="text-muted-foreground">owns it</span>
        </span>
      </div>

      {open.length > 0 && (
        <Panel title="Open incidents">
          <IncidentRows incidents={open} />
        </Panel>
      )}

      <div className="grid items-start gap-5 lg:grid-cols-2">
        <OnCallPanel serviceId={service.id} />
        <Panel title="Service-level targets">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-[19px]">Severity</TableHead>
                  <TableHead>Response</TableHead>
                  <TableHead className="pr-[19px]">Resolution</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {SEVERITIES.map((severity) => (
                  <TableRow key={severity}>
                    <TableCell className="pl-[19px]">
                      <SeverityBadge severity={severity} />
                    </TableCell>
                    <TableCell>{formatMinutes(service.targets[severity].responseMinutes)}</TableCell>
                    <TableCell className="pr-[19px]">{formatMinutes(service.targets[severity].resolutionMinutes)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <PanelFooter>New incidents copy these targets; open incidents keep theirs.</PanelFooter>
        </Panel>
      </div>

      {past.length > 0 && (
        <Panel title="Past incidents">
          <IncidentRows incidents={past} />
        </Panel>
      )}

      {canConfigure && <ServiceFormDialog service={service} open={editing} onOpenChange={setEditing} />}
    </div>
  )
}
