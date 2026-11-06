import { IncidentTelemetry } from './IncidentTelemetry'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import {
  addIncidentEvidence,
  getIncident,
  linkIncidentDeploy,
  type Incident,
  type TimelineEntry,
} from '@/api/incidents'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { formatIst, formatMinutes } from '@/lib/time'

const eventLabel = (eventType: string) => {
  const words = eventType.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function Detail({
  label,
  children,
}: {
  label: string
  children: ReactNode
}) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs font-medium text-muted-foreground">
        {label}
      </dt>
      <dd className="text-sm">{children}</dd>
    </div>
  )
}

function SlaDue({
  label,
  dueAt,
  targetMinutes,
}: {
  label: string
  dueAt: string
  targetMinutes: number
}) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border bg-card p-4">
      <span className="text-xs font-medium text-muted-foreground">
        {label}
      </span>
      <span className="font-mono text-lg font-semibold tnum">
        {formatIst(dueAt)}
      </span>
      <span className="text-xs text-muted-foreground">
        Target {formatMinutes(targetMinutes)} from declaration
      </span>
    </div>
  )
}

function Timeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        Nothing recorded yet.
      </p>
    )
  }

  return (
    <ol className="flex flex-col gap-4 border-l pl-5">
      {entries.map((entry) => (
        <li
          key={entry.id}
          className="relative flex flex-col gap-0.5"
        >
          <span
            aria-hidden="true"
            className="absolute top-1.5 -left-[25px] size-2 rounded-full bg-foreground"
          />

          <span className="text-sm">
            <span className="font-semibold">
              {eventLabel(entry.eventType)}
            </span>
            <span className="text-muted-foreground">
              {' · '}{entry.actor ?? 'System'}
            </span>
          </span>

          {entry.details && (
            <span className="text-sm">{entry.details}</span>
          )}

          <span className="font-mono text-xs text-muted-foreground tnum">
            {formatIst(entry.recordedAt)}
          </span>
        </li>
      ))}
    </ol>
  )
}

function Deploys({ incident }: { incident: Incident }) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const allowed = can(user?.role, 'respond')
  const deploys = incident.deploys ?? []

  const linkMutation = useMutation({
    mutationFn: (deployId: number) =>
      linkIncidentDeploy(incident.id, deployId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ['incident', incident.id],
      })
    },
  })

  return (
    <section
      aria-labelledby="deploys-heading"
      className="flex flex-col gap-3"
    >
      <h2 id="deploys-heading" className="text-lg font-semibold">
        Deploys before incident
      </h2>

      {deploys.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No deploys found in the 2 hours before this incident.
        </p>
      ) : (
        <ul className="flex flex-col gap-3">
          {deploys.map((deploy) => (
            <li
              key={deploy.id}
              className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-card p-4"
            >
              <div className="flex flex-col gap-1">
                <p className="font-medium">{deploy.version}</p>

                <p className="font-mono text-xs text-muted-foreground">
                  Commit: {deploy.commit}
                </p>

                <p className="text-sm text-muted-foreground">
                  By {deploy.author} · {formatIst(deploy.deployedAt)}
                </p>
              </div>

              {allowed && (
                <button
                  type="button"
                  disabled={linkMutation.isPending}
                  onClick={() => linkMutation.mutate(deploy.id)}
                  className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
                >
                  {linkMutation.isPending &&
                  linkMutation.variables === deploy.id
                    ? 'Linking...'
                    : 'Link'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {linkMutation.isError && (
        <p role="alert" className="text-sm text-destructive">
          Could not link deploy. Please try again.
        </p>
      )}

      {linkMutation.isSuccess && (
        <p role="status" className="text-sm text-muted-foreground">
          Deploy linked successfully.
        </p>
      )}
    </section>
  )
}

function EvidenceLinks({
  incidentId,
  entries,
}: {
  incidentId: number
  entries: TimelineEntry[]
}) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [label, setLabel] = useState('')
  const [url, setUrl] = useState('')

  const evidenceEntries = entries.filter(
    (entry) => entry.eventType === 'EVIDENCE_ADDED',
  )

  const addEvidence = useMutation({
    mutationFn: () =>
      addIncidentEvidence(incidentId, {
        label: label.trim(),
        url: url.trim(),
      }),
    onSuccess: async () => {
      setLabel('')
      setUrl('')
      await queryClient.invalidateQueries({
        queryKey: ['incident', incidentId],
      })
    },
  })

  return (
    <section
      aria-labelledby="evidence-heading"
      className="flex flex-col gap-3"
    >
      <h2 id="evidence-heading" className="text-lg font-semibold">
        Evidence Links
      </h2>

      {evidenceEntries.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No evidence links added yet.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {evidenceEntries.map((entry) => (
            <li
              key={entry.id}
              className="rounded-lg border bg-card p-3"
            >
              <p className="text-sm">
  {(() => {
    const details = entry.details ?? 'Evidence added'
    const match = details.match(/https?:\/\/[^\s<>"']+/i)
    const evidenceUrl = match?.[0]

    if (!evidenceUrl) return details

    return (
      <>
        {details.slice(0, match.index)}
        <a
          href={evidenceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="text-primary underline hover:opacity-80"
        >
          {evidenceUrl}
        </a>
        {details.slice((match.index ?? 0) + evidenceUrl.length)}
      </>
    )
  })()}
</p>

              <p className="mt-1 text-xs text-muted-foreground">
                Added by {entry.actor ?? 'System'} ·{' '}
                {formatIst(entry.recordedAt)}
              </p>
            </li>
          ))}
        </ul>
      )}

      {can(user?.role, 'respond') && (
        <form
          className="flex flex-col gap-3 rounded-lg border bg-card p-4"
          onSubmit={(event) => {
            event.preventDefault()
            if (!label.trim() || !url.trim()) return
            addEvidence.mutate()
          }}
        >
          <h3 className="font-medium">Add evidence</h3>

          <div className="flex flex-col gap-1">
            <label
              htmlFor="evidence-label"
              className="text-sm font-medium"
            >
              Label
            </label>

            <input
              id="evidence-label"
              value={label}
              onChange={(event) => setLabel(event.target.value)}
              placeholder="Example: Error logs"
              required
              maxLength={200}
              className="rounded-md border bg-background p-2 text-sm"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label
              htmlFor="evidence-url"
              className="text-sm font-medium"
            >
              URL
            </label>

            <input
              id="evidence-url"
              type="url"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://example.com/logs"
              required
              pattern="https?://.+"
              title="Use an HTTP or HTTPS link"
              className="rounded-md border bg-background p-2 text-sm"
            />
          </div>

          <button
            type="submit"
            disabled={addEvidence.isPending}
            className="w-fit rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
          >
            {addEvidence.isPending ? 'Adding...' : 'Add evidence'}
          </button>

          {addEvidence.isError && (
            <p role="alert" className="text-sm text-destructive">
              {addEvidence.error.message}
            </p>
          )}
        </form>
      )}
    </section>
  )
}

export function IncidentDetailPage() {
  const id = Number(useParams().id)
  const valid = Number.isInteger(id) && id > 0

  const incident = useQuery({
    queryKey: ['incident', id],
    queryFn: () => getIncident(id),
    enabled: valid,
  })

  const notFound =
    !valid ||
    (incident.error instanceof ApiRequestError &&
      incident.error.status === 404)

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <Link
        to="/incidents"
        className="flex w-fit items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Incidents
      </Link>

      {notFound ? (
        <ErrorState
          title="Incident not found"
          message="It may have been mistyped. Check the incident list."
        />
      ) : incident.isPending ? (
        <LoadingState message="Loading incident" />
      ) : incident.isError ? (
        <ErrorState
          message={incident.error.message}
          onRetry={() => incident.refetch()}
        />
      ) : (
        <>
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm text-muted-foreground">
                {incident.data.reference}
              </span>

              <SeverityBadge severity={incident.data.severity} />
              <StatusBadge status={incident.data.status} />
            </div>

            <h1
              className="text-[40px] leading-[0.95] font-[750]"
              style={{ fontStretch: '62%' }}
            >
              {incident.data.title}
            </h1>

            <p className="max-w-[760px] whitespace-pre-line text-muted-foreground">
              {incident.data.description}
            </p>
          </div>

          <dl className="grid gap-4 rounded-lg border bg-card p-4 sm:grid-cols-4">
            <Detail label="Service">
              {incident.data.service.name}
            </Detail>

            <Detail label="Reporter">
              {incident.data.reporter?.displayName ?? 'System'}
            </Detail>

            <Detail label="Assignee">
              {incident.data.assignee?.displayName ?? 'Unassigned'}
            </Detail>

            <Detail label="Declared">
              <span className="font-mono tnum">
                {formatIst(incident.data.createdAt)}
              </span>
            </Detail>
          </dl>

          <section
            aria-labelledby="sla-heading"
            className="flex flex-col gap-3"
          >
            <h2 id="sla-heading" className="text-lg font-semibold">
              SLA
            </h2>

            <div className="grid gap-4 sm:grid-cols-2">
              <SlaDue
                label="Response due"
                dueAt={incident.data.responseDueAt}
                targetMinutes={incident.data.responseTargetMinutes}
              />

              <SlaDue
                label="Resolution due"
                dueAt={incident.data.resolutionDueAt}
                targetMinutes={incident.data.resolutionTargetMinutes}
              />
            </div>
          </section>

          <Deploys incident={incident.data} />
          <IncidentTelemetry
          serviceId={incident.data.service.id}
          createdAt={incident.data.createdAt}
          />
          <EvidenceLinks
            incidentId={id}
            entries={incident.data.timeline}
          />

          <section
            aria-labelledby="timeline-heading"
            className="flex flex-col gap-3"
          >
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
