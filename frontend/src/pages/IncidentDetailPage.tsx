import { useQuery } from '@tanstack/react-query'
import { Activity, ArrowLeft, Check } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { getIncident } from '@/api/incidents'
import { listRunbooks } from '@/api/runbooks'
import { useAuth } from '@/auth/AuthProvider'
import { Avatar } from '@/components/app/Avatar'
import { LIFECYCLE, SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Panel } from '@/components/app/Panel'
import { SimilarIncidentsPanel } from '@/components/app/SimilarIncidentsPanel'
import type { IncidentStatus } from '@/api/schemas'
import { cn } from '@/lib/utils'
import { formatIst } from '@/lib/time'
import { IncidentActions } from './incident/IncidentActions'
import { IncidentAlerts } from './incident/IncidentAlerts'
import { IncidentDeploys } from './incident/IncidentDeploys'
import { IncidentEvidence } from './incident/IncidentEvidence'
import { IncidentTelemetry } from './incident/IncidentTelemetry'
import { IncidentTimeline } from './incident/IncidentTimeline'
import { PublicUpdates } from './incident/PublicUpdateForm'
import { SlaClocks } from './incident/SlaClocks'

/** The seven steps, done ones checked and the current one numbered (the preview's lifecycle bar). */
function Lifecycle({ status }: { status: IncidentStatus }) {
  const current = LIFECYCLE.indexOf(status)
  return (
    <ol aria-label="Lifecycle" className="flex gap-4 overflow-x-auto rounded-lg border bg-card px-[19px] py-3.5 text-[12px]">
      {LIFECYCLE.map((step, index) => (
        <li
          key={step}
          aria-current={index === current ? 'step' : undefined}
          className={cn(
            'flex shrink-0 items-center gap-1.5 whitespace-nowrap text-muted-foreground',
            index < current && 'text-status-resolved',
            index === current && 'font-medium text-foreground',
          )}
        >
          <span
            aria-hidden="true"
            className={cn('grid size-[18px] place-items-center rounded-full border font-mono text-[10px]', index === current && 'border-foreground')}
          >
            {index < current ? <Check className="size-3" /> : index + 1}
          </span>
          {step.charAt(0) + step.slice(1).toLowerCase()}
        </li>
      ))}
    </ol>
  )
}

/** One incident: what it is, what a responder can do to it, both SLA clocks, and its timeline. */
// Show runbooks belonging to the incident's service.
function IncidentRunbooks({
  serviceId,
}: {
  serviceId: number
}) {
  const runbooks = useQuery({
    queryKey: ['runbooks', serviceId],
    queryFn: () => listRunbooks(serviceId),
  })

  return (
    <section
      aria-labelledby="incident-runbooks-heading"
      className="flex flex-col gap-3"
    >
      <h2
        id="incident-runbooks-heading"
        className="text-[13px] font-medium"
      >
        Suggested runbooks
      </h2>

      {runbooks.isPending ? (
        <LoadingState message="Loading runbooks" />
      ) : runbooks.isError ? (
        <ErrorState
          message={runbooks.error.message}
          onRetry={() => void runbooks.refetch()}
        />
      ) : runbooks.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No runbooks available for this service.
        </p>
      ) : (
        <div className="grid gap-4">
          {runbooks.data.map((runbook) => (
            <article
              key={runbook.id}
              className="rounded-lg border bg-card p-4"
            >
              <h3 className="font-semibold">
                {runbook.title}
              </h3>

              <ol className="mt-3 list-decimal space-y-2 pl-6 text-sm">
                {runbook.steps.map((step, index) => (
                  <li key={index}>{step}</li>
                ))}
              </ol>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}

export function IncidentDetailPage() {
  const { user, org } = useAuth()
  const id = Number(useParams().id)
  const valid = Number.isInteger(id) && id > 0
  const incident = useQuery({ queryKey: ['incident', id], queryFn: () => getIncident(id), enabled: valid })
  const notFound = !valid || (incident.error instanceof ApiRequestError && incident.error.status === 404)

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <Link to="/incidents" className="flex w-fit items-center gap-1.5 text-[12px] text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-3.5" aria-hidden="true" />
        All incidents
      </Link>

      {notFound ? (
        <ErrorState title="Incident not found" message="It may have been mistyped. Check the incident list." />
      ) : incident.isPending ? (
        <LoadingState message="Loading incident" />
      ) : incident.isError ? (
        <ErrorState message={incident.error.message} onRetry={() => incident.refetch()} />
      ) : (
        <>
          <header className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center justify-between gap-2 text-[12px] text-muted-foreground">
              <span className="flex items-center gap-2">
                <span className="font-mono">{incident.data.reference}</span>
                <span aria-hidden="true">/</span>
                <span>{incident.data.service.name}</span>
              </span>
              <span>
                Declared <span className="font-mono tnum">{formatIst(incident.data.createdAt)}</span> · by{' '}
                {incident.data.reporter?.displayName ?? 'the system'}
              </span>
            </div>
            <h1 className="text-[26px] leading-[1.15] font-medium tracking-[-0.035em] nav:text-[30px]">{incident.data.title}</h1>
            <div className="flex flex-wrap items-center gap-3 text-[13px]">
              <SeverityBadge severity={incident.data.severity} />
              <StatusBadge status={incident.data.status} />
              <span aria-hidden="true" className="text-muted-foreground">·</span>
              {incident.data.assignee ? (
                <span className="flex items-center gap-2">
                  <Avatar name={incident.data.assignee.displayName} size="sm" />
                  <span>
                    <span className="sr-only">Lead: </span>
                    {incident.data.assignee.displayName}
                  </span>
                </span>
              ) : (
                <span className="text-muted-foreground">No Lead yet</span>
              )}
            </div>
          </header>

          <Lifecycle status={incident.data.status} />

          <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_290px]">
            <div className="flex min-w-0 flex-col gap-6">
              <Panel
                title={
                  <>
                    <Activity className="size-4 text-muted-foreground" aria-hidden="true" />
                    Current impact
                  </>
                }
              >
                <p className="px-[19px] pb-[18px] text-[14px] leading-relaxed whitespace-pre-line">{incident.data.description}</p>
              </Panel>

              <section aria-labelledby="timeline-heading" className="flex flex-col gap-3">
                <h2 id="timeline-heading" className="text-[13px] font-medium">
                  Timeline
                </h2>
                <IncidentTimeline incidentId={incident.data.id} entries={incident.data.timeline} />
              </section>

              <IncidentAlerts incidentId={incident.data.id} />

              <IncidentDeploys incident={incident.data} />

              <IncidentEvidence incidentId={incident.data.id} timeline={incident.data.timeline} />

              <IncidentTelemetry serviceId={incident.data.service.id} createdAt={incident.data.createdAt} />

              {user && user.role !== 'VIEWER' && (
                <PublicUpdates
                  incidentId={incident.data.id}
                  orgSlug={org?.slug}
                  // The same rule as the server's: an Admin, or this incident's Lead or Comms.
                  mayPublish={
                    user.role === 'ADMIN' || incident.data.assignee?.id === user.id || incident.data.roles?.comms?.id === user.id
                  }
                />
              )}
            </div>

            <aside aria-label="Clocks and ownership" className="flex min-w-0 flex-col gap-6">
              <section aria-labelledby="sla-heading" className="flex flex-col gap-3">
                <h2 id="sla-heading" className="text-[13px] font-medium">
                  SLA
                </h2>
                <SlaClocks incident={incident.data} className="grid gap-3" />
                <p className="text-[12px] text-muted-foreground">Targets were copied when the incident was declared and stay fixed.</p>
              </section>

              <IncidentActions incident={incident.data} />

              <IncidentRunbooks serviceId={incident.data.service.id} />

              <SimilarIncidentsPanel incidentId={incident.data.id} />
            </aside>
          </div>
        </>
      )}
    </div>
  )
}
