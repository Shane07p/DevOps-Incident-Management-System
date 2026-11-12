import { useQuery } from '@tanstack/react-query'
import { CircleCheck, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import {
  getPublicStatus,
  PUBLIC_UPDATE_LABEL,
  type PublicHealth,
  type PublicStatus,
  type PublicUpdateStatus,
  type PublishedIncident,
  type PublishedService,
} from '@/api/publicStatus'
import { useAuth } from '@/auth/AuthProvider'
import { UptimeBars, type MeasuredDay } from '@/components/app/UptimeBars'
import { ThemeToggle } from '@/components/shell/ThemeToggle'
import type { DayState } from '@/lib/statusTypes'
import { formatIst } from '@/lib/time'
import { cn } from '@/lib/utils'

const HEALTH_STYLE: Record<PublicHealth, { dot: string; text: string; label: string }> = {
  OPERATIONAL: { dot: 'bg-health-operational', text: 'text-health-operational-fg', label: 'Operational' },
  DEGRADED: { dot: 'bg-health-degraded', text: 'text-health-degraded-fg', label: 'Degraded' },
  DOWN: { dot: 'bg-health-down', text: 'text-health-down-fg', label: 'Major outage' },
  UNKNOWN: { dot: 'bg-muted-foreground', text: 'text-muted-foreground', label: 'No data yet' },
}

const UPDATE_COLOR: Record<PublicUpdateStatus, string> = {
  INVESTIGATING: 'text-status-investigating',
  IDENTIFIED: 'text-status-identified',
  MONITORING: 'text-status-monitoring',
  RESOLVED: 'text-status-resolved',
}

const sectionTitle = 'text-[20px] leading-none font-[750]'
const condensed = { fontStretch: '75%' } as const

const percent = (value: number) => (value === 100 ? '100%' : `${value.toFixed(2)}%`)

// The server counts days in UTC, so the label does too.
const utcDayFormat = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' })

/** A day with no checks is grey; any failed check makes it amber, and losing more than 5% of checks makes it red. */
function dayState(uptimePercent: number | null): DayState {
  if (uptimePercent === null) return 'none'
  if (uptimePercent === 100) return 'ok'
  return uptimePercent >= 95 ? 'degraded' : 'down'
}

function measuredDays(service: PublishedService): MeasuredDay[] {
  return service.uptimeDays.map((day) => ({
    label: utcDayFormat.format(new Date(`${day.date}T00:00:00Z`)),
    state: dayState(day.uptimePercent),
    detail: day.uptimePercent === null ? 'no checks' : `${percent(day.uptimePercent)} uptime`,
  }))
}

/** The banner: the worst service decides it, and an open incident means something is affected. */
function summary(data: PublicStatus) {
  const affected = new Set(data.activeIncidents.map((incident) => incident.serviceName))
  for (const service of data.services) {
    if (service.health === 'DOWN' || service.health === 'DEGRADED') affected.add(service.name)
  }
  const names = [...affected]
  const text = names.length === 0 ? `${data.orgName} service status` : `${names.join(', ')} ${names.length === 1 ? 'is' : 'are'} affected.`
  if (data.services.some((service) => service.health === 'DOWN')) {
    return { title: 'Major outage', text, tone: 'bg-sev-1-bg text-sev-1-fg', ok: false }
  }
  if (names.length > 0) {
    return { title: 'Partial outage', text, tone: 'bg-sev-3-bg text-sev-3-fg', ok: false }
  }
  return { title: 'All systems operational', text, tone: 'bg-status-resolved-bg text-status-resolved', ok: true }
}

function ActiveIncident({ incident }: { incident: PublishedIncident }) {
  // Updates come newest first, so the first one posted is last.
  const first = incident.updates.at(-1)
  return (
    <article className="flex flex-col rounded-lg border bg-card">
      <div className="flex flex-col gap-1.5 border-b px-5 py-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <span className="text-base font-semibold">{incident.serviceName}</span>
          <span className={cn('text-[13px] font-bold', UPDATE_COLOR[incident.status])}>{PUBLIC_UPDATE_LABEL[incident.status]}</span>
        </div>
        {first && <span className="font-mono text-xs font-medium text-muted-foreground">Started {formatIst(first.createdAt)}</span>}
      </div>
      <ol className="px-5 pt-1.5 pb-2.5">
        {incident.updates.map((update, index) => (
          <li key={update.id} className={cn('grid gap-1 py-3 sm:grid-cols-[150px_minmax(0,1fr)] sm:gap-4', index > 0 && 'border-t')}>
            <div className="flex flex-col gap-0.5">
              <span className={cn('text-[13px] font-bold', UPDATE_COLOR[update.status])}>{PUBLIC_UPDATE_LABEL[update.status]}</span>
              <time dateTime={update.createdAt} className="font-mono text-[11.5px] font-medium text-muted-foreground">
                {formatIst(update.createdAt)}
              </time>
            </div>
            <p className="text-pretty whitespace-pre-line">{update.message}</p>
          </li>
        ))}
      </ol>
    </article>
  )
}

function StatusContent({ data }: { data: PublicStatus }) {
  const banner = summary(data)
  const Icon = banner.ok ? CircleCheck : TriangleAlert
  return (
    <>
      <div className={cn('flex items-center gap-3.5 rounded-lg px-5 py-5', banner.tone)}>
        <Icon className="size-[22px] shrink-0" aria-hidden="true" />
        <div className="flex flex-col gap-0.5">
          <h1 className="text-[24px] leading-[1.1] font-[750]" style={condensed}>
            {banner.title}
          </h1>
          <span className="text-[13px]">{banner.text}</span>
        </div>
      </div>

      {data.activeIncidents.length > 0 && (
        <section className="flex flex-col gap-3.5">
          <h2 className={sectionTitle} style={condensed}>
            {data.activeIncidents.length === 1 ? 'Active incident' : 'Active incidents'}
          </h2>
          {data.activeIncidents.map((incident) => (
            <ActiveIncident key={incident.id} incident={incident} />
          ))}
        </section>
      )}

      <section className="flex flex-col gap-3.5">
        <div className="flex items-baseline justify-between gap-3">
          <h2 className={sectionTitle} style={condensed}>
            Components
          </h2>
          <span className="text-[12.5px] text-muted-foreground">Last 90 days</span>
        </div>
        <div className="overflow-hidden rounded-lg border bg-card">
          {data.services.length === 0 ? (
            <p className="px-5 py-4 text-muted-foreground">No services are on this page yet.</p>
          ) : (
            data.services.map((service, index) => {
              const style = HEALTH_STYLE[service.health]
              const uptime = service.uptimePercent === null ? undefined : percent(service.uptimePercent)
              return (
                <div key={service.id} className={cn('flex flex-col gap-2.5 px-5 py-4', index > 0 && 'border-t')}>
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-semibold">{service.name}</span>
                    <span className={cn('inline-flex items-center gap-[7px] text-[13px] font-semibold whitespace-nowrap', style.text)}>
                      <span className={cn('size-2 rounded-full', style.dot)} aria-hidden="true" />
                      {style.label}
                    </span>
                  </div>
                  <UptimeBars name={service.name} days={measuredDays(service)} uptime={uptime} />
                </div>
              )
            })
          )}
        </div>
      </section>

      <section className="flex flex-col gap-3.5">
        <h2 className={sectionTitle} style={condensed}>
          Past incidents
        </h2>
        <div className="overflow-hidden rounded-lg border bg-card">
          {data.resolvedIncidents.length === 0 ? (
            <p className="px-5 py-4 text-muted-foreground">No incidents in the last 90 days.</p>
          ) : (
            data.resolvedIncidents.map((incident, index) => (
              <div key={incident.id} className={cn('flex flex-col gap-0.5 px-5 py-3.5', index > 0 && 'border-t')}>
                <span className="font-semibold">{incident.serviceName}</span>
                {incident.updates[0] && <span className="text-[13px] text-pretty">{incident.updates[0].message}</span>}
                {incident.resolvedAt && (
                  <time dateTime={incident.resolvedAt} className="font-mono text-xs font-medium text-muted-foreground">
                    Resolved {formatIst(incident.resolvedAt)}
                  </time>
                )}
              </div>
            ))
          )}
        </div>
      </section>
    </>
  )
}

function Message({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div role="alert" className="flex flex-col gap-2 rounded-lg border bg-card px-5 py-5">
      <h1 className="text-[24px] leading-[1.1] font-[750]" style={condensed}>
        {title}
      </h1>
      {children}
    </div>
  )
}

/** The page with no org in its address: the signed-in user's own page is one click away. */
function NoSlug() {
  const { org } = useAuth()
  return (
    <Message title="Which status page?">
      <p className="text-muted-foreground">Each organization on Settle has its own page at /status/ followed by its name.</p>
      {org && (
        <Link to={`/status/${org.slug}`} className="w-fit font-semibold text-primary underline">
          Open {org.name}&apos;s status page
        </Link>
      )}
    </Message>
  )
}

/** An org's public page, `/status/{slug}`, that customers check during an incident. No login. */
export function StatusPage() {
  const { slug } = useParams<{ slug: string }>()
  const status = useQuery({
    queryKey: ['public-status', slug],
    queryFn: () => getPublicStatus(slug!),
    enabled: Boolean(slug),
    retry: false,
  })
  const notFound = status.error instanceof ApiRequestError && status.error.status === 404

  return (
    <div className="min-h-screen bg-background text-[14px] text-foreground">
      <div className="mx-auto flex max-w-[880px] flex-col gap-8 px-4 pt-10 pb-16 sm:px-6">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-baseline gap-2.5">
            <span className="text-[30px] leading-none font-extrabold tracking-[-0.01em]" style={{ fontStretch: '70%' }}>
              settle
            </span>
            <span className="text-[15px] font-semibold text-muted-foreground">status</span>
          </div>
          <ThemeToggle />
        </header>

        {!slug ? (
          <NoSlug />
        ) : notFound ? (
          <Message title="Status page not found">
            <p className="text-muted-foreground">No organization uses this address. Check the link and try again.</p>
          </Message>
        ) : status.isPending ? (
          <p role="status" className="text-muted-foreground">
            Loading status
          </p>
        ) : status.isError ? (
          <Message title="Status could not be loaded">
            <p className="text-muted-foreground">{status.error.message}</p>
            <button type="button" className="w-fit font-semibold text-primary underline" onClick={() => status.refetch()}>
              Try again
            </button>
          </Message>
        ) : (
          <StatusContent data={status.data} />
        )}

        <footer className="flex flex-wrap justify-between gap-3 pt-2 text-[12.5px] text-muted-foreground">
          <span>Times in IST. Uptime days are UTC days.</span>
          <Link to="/" className="font-semibold">
            Powered by Settle
          </Link>
        </footer>
      </div>
    </div>
  )
}
