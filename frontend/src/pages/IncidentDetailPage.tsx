import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { getIncident } from '@/api/incidents'
import { listRunbooks } from '@/api/runbooks'
import { useAuth } from '@/auth/AuthProvider'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { SimilarIncidentsPanel } from '@/components/app/SimilarIncidentsPanel'
import { formatIst, formatMinutes } from '@/lib/time'
import { IncidentTimeline } from './incident/IncidentTimeline'
import { PublicUpdates } from './incident/PublicUpdateForm'

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  )
}

function SlaDue({ label, dueAt, targetMinutes }: { label: string; dueAt: string; targetMinutes: number }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border bg-card p-4">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <span className="font-mono text-lg font-semibold tnum">{formatIst(dueAt)}</span>
      <span className="text-xs text-muted-foreground">Target {formatMinutes(targetMinutes)} from declaration</span>
    </div>
  )
}

/** One incident: what it is, both SLA due times, and its timeline. Actions arrive in Sprint 3 (#89). */
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
        className="text-lg font-semibold"
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
      <Link to="/incidents" className="flex w-fit items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" aria-hidden="true" />
        Incidents
      </Link>

      {notFound ? (
        <ErrorState title="Incident not found" message="It may have been mistyped. Check the incident list." />
      ) : incident.isPending ? (
        <LoadingState message="Loading incident" />
      ) : incident.isError ? (
        <ErrorState message={incident.error.message} onRetry={() => incident.refetch()} />
      ) : (
        <>
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm text-muted-foreground">{incident.data.reference}</span>
              <SeverityBadge severity={incident.data.severity} />
              <StatusBadge status={incident.data.status} />
            </div>
            <h1 className="text-[40px] leading-[0.95] font-[750]" style={{ fontStretch: '62%' }}>
              {incident.data.title}
            </h1>
            <p className="max-w-[760px] whitespace-pre-line text-muted-foreground">{incident.data.description}</p>
          </div>

          <dl className="grid gap-4 rounded-lg border bg-card p-4 sm:grid-cols-4">
            <Detail label="Service">{incident.data.service.name}</Detail>
            <Detail label="Reporter">{incident.data.reporter?.displayName ?? 'System'}</Detail>
            <Detail label="Assignee">{incident.data.assignee?.displayName ?? 'Unassigned'}</Detail>
            <Detail label="Declared">
              <span className="font-mono tnum">{formatIst(incident.data.createdAt)}</span>
            </Detail>
          </dl>

          <section aria-labelledby="sla-heading" className="flex flex-col gap-3">
            <h2 id="sla-heading" className="text-lg font-semibold">
              SLA
            </h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <SlaDue label="Response due" dueAt={incident.data.responseDueAt} targetMinutes={incident.data.responseTargetMinutes} />
              <SlaDue
                label="Resolution due"
                dueAt={incident.data.resolutionDueAt}
                targetMinutes={incident.data.resolutionTargetMinutes}
              />
            </div>
          </section>

          <IncidentRunbooks serviceId={incident.data.service.id} />

          <SimilarIncidentsPanel incidentId={incident.data.id} />

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

          <section aria-labelledby="timeline-heading" className="flex flex-col gap-3">
            <h2 id="timeline-heading" className="text-lg font-semibold">
              Timeline
            </h2>
            <IncidentTimeline incidentId={incident.data.id} entries={incident.data.timeline} />
          </section>
        </>
      )}
    </div>
  )
}
