import { useQuery } from '@tanstack/react-query'
import { CircleCheck, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import {
  getPublicStatus,
  type PublicStatus,
  type PublishedIncident,
  type PublishedService,
} from '@/api/publicStatus'
import { useAuth } from '@/auth/AuthProvider'
import { Health } from '@/components/app/Health'
import { Panel } from '@/components/app/Panel'
import { PublicUpdateBadge } from '@/components/app/PublicUpdateBadge'
import { UptimeBars, type MeasuredDay } from '@/components/app/UptimeBars'
import { ThemeToggle } from '@/components/shell/ThemeToggle'
import { Wordmark } from '@/components/shell/Wordmark'
import type { DayState } from '@/lib/statusTypes'
import { PUBLIC_HEALTH, publicStatusSummary } from '@/lib/publicStatus'
import { formatIst } from '@/lib/time'
import { cn } from '@/lib/utils'

// The banner is a card like the rest; only its border and icon take the colour of the state.
const BANNER: Record<'ok' | 'warn' | 'fail', { border: string; icon: string }> = {
  ok: { border: '', icon: 'text-health-operational-fg' },
  warn: { border: 'border-health-degraded/45', icon: 'text-health-degraded-fg' },
  fail: { border: 'border-health-down/45', icon: 'text-health-down-fg' },
}

const pageTitle = 'text-[28px] leading-[1.1] font-medium tracking-[-0.035em] nav:text-[34px]'

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

function ActiveIncident({ incident }: { incident: PublishedIncident }) {
  // Updates come newest first, so the first one posted is last.
  const first = incident.updates.at(-1)
  return (
    <article className="flex flex-col rounded-lg border bg-card shadow-elev-1">
      <div className="flex flex-col gap-1.5 border-b px-5 py-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <span className="text-[15px] font-medium">{incident.serviceName}</span>
          <PublicUpdateBadge status={incident.status} />
        </div>
        {first && <span className="font-mono text-xs font-medium text-muted-foreground">Started {formatIst(first.createdAt)}</span>}
      </div>
      <ol className="px-5 pt-1.5 pb-2.5">
        {incident.updates.map((update, index) => (
          <li key={update.id} className={cn('grid gap-1 py-3 sm:grid-cols-[150px_minmax(0,1fr)] sm:gap-4', index > 0 && 'border-t')}>
            <div className="flex flex-col gap-0.5">
              <PublicUpdateBadge status={update.status} />
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
  const banner = publicStatusSummary(data)
  const Icon = banner.tone === 'ok' ? CircleCheck : TriangleAlert
  return (
    <>
      <div className="flex flex-col gap-5">
        <h1 className={pageTitle}>System status</h1>
        <div className={cn('flex items-center gap-[15px] rounded-lg border bg-card p-[22px] shadow-elev-1', BANNER[banner.tone].border)}>
          <Icon className={cn('size-[22px] shrink-0', BANNER[banner.tone].icon)} aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <h2 className="text-[17px] leading-tight font-medium tracking-[-0.01em]">{banner.title}</h2>
            <span className="text-[13px] text-muted-foreground">{banner.text}</span>
          </div>
        </div>
      </div>

      {data.activeIncidents.length > 0 && (
        <section aria-labelledby="active-incidents" className="flex flex-col gap-3.5">
          <h2 id="active-incidents" className="text-[18px] font-medium tracking-[-0.02em]">
            {data.activeIncidents.length === 1 ? 'Active incident' : 'Active incidents'}
          </h2>
          {data.activeIncidents.map((incident) => (
            <ActiveIncident key={incident.id} incident={incident} />
          ))}
        </section>
      )}

      <Panel title="Components" action={<span className="text-[12px] text-muted-foreground">Last 90 days</span>}>
        {data.services.length === 0 ? (
          <p className="border-t px-5 py-4 text-muted-foreground">No services are on this page yet.</p>
        ) : (
          data.services.map((service) => {
            const health = PUBLIC_HEALTH[service.health]
            const uptime = service.uptimePercent === null ? undefined : percent(service.uptimePercent)
            return (
              <div key={service.id} className="flex flex-col gap-2.5 border-t px-5 py-3.5">
                <div className="flex items-center justify-between gap-3">
                  <span className="font-medium">{service.name}</span>
                  <Health tone={health.tone} className="text-[13px] whitespace-nowrap">
                    {health.label}
                  </Health>
                </div>
                <UptimeBars name={service.name} days={measuredDays(service)} uptime={uptime} />
              </div>
            )
          })
        )}
      </Panel>

      <Panel title="Past incidents">
        {data.resolvedIncidents.length === 0 ? (
          <p className="border-t px-5 py-4 text-muted-foreground">No incidents in the last 90 days.</p>
        ) : (
          data.resolvedIncidents.map((incident) => (
            <div key={incident.id} className="flex flex-col gap-0.5 border-t px-5 py-3.5">
              <span className="font-medium">{incident.serviceName}</span>
              {incident.updates[0] && <span className="text-[13px] text-pretty">{incident.updates[0].message}</span>}
              {incident.resolvedAt && (
                <time dateTime={incident.resolvedAt} className="font-mono text-xs text-muted-foreground">
                  Resolved {formatIst(incident.resolvedAt)}
                </time>
              )}
            </div>
          ))
        )}
      </Panel>
    </>
  )
}

function Message({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div role="alert" className="flex flex-col gap-2 rounded-lg border bg-card px-5 py-5 shadow-elev-1">
      <h1 className="text-[22px] leading-[1.1] font-medium tracking-[-0.03em]">{title}</h1>
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
      <div className="mx-auto flex max-w-[820px] flex-col gap-7 px-4 pt-6 pb-16 sm:px-6">
        <header className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <Link to="/" aria-label="Settle home" className="text-foreground hover:no-underline">
              <Wordmark size={24} />
            </Link>
            <span className="text-muted-foreground">Status</span>
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
          <Link to="/" className="font-medium">
            Powered by Settle
          </Link>
        </footer>
      </div>
    </div>
  )
}
