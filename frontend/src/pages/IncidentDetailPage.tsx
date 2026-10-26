import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { getIncident, type TimelineEntry } from '@/api/incidents'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { formatIst, formatMinutes } from '@/lib/time'

// "INCIDENT_CREATED" → "Incident created"; unknown types from later sprints still read sensibly.
const eventLabel = (eventType: string) => {
  const words = eventType.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

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

function Timeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) return <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>
  return (
    <ol className="flex flex-col gap-4 border-l pl-5">
      {entries.map((entry) => (
        <li key={entry.id} className="relative flex flex-col gap-0.5">
          <span aria-hidden="true" className="absolute top-1.5 -left-[25px] size-2 rounded-full bg-foreground" />
          <span className="text-sm">
            <span className="font-semibold">{eventLabel(entry.eventType)}</span>
            <span className="text-muted-foreground"> · {entry.actor ?? 'System'}</span>
          </span>
          {entry.details && <span className="text-sm">{entry.details}</span>}
          <span className="font-mono text-xs text-muted-foreground tnum">{formatIst(entry.recordedAt)}</span>
        </li>
      ))}
    </ol>
  )
}

/** One incident: what it is, both SLA due times, and its timeline. Actions arrive in Sprint 3 (#89). */
export function IncidentDetailPage() {
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

          <section aria-labelledby="timeline-heading" className="flex flex-col gap-3">
            <h2 id="timeline-heading" className="text-lg font-semibold">
              Timeline
            </h2>
            <Timeline entries={incident.data.timeline} />
          </section>
        </>
      )}
    </div>
  )
}
