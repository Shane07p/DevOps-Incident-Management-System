import { useQuery } from '@tanstack/react-query'
import { TriangleAlert } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { getPublicStatus, type PublicStatus } from '@/api/publicStatus'
import { ThemeToggle } from '@/components/shell/ThemeToggle'
import { cn } from '@/lib/utils'

const HEALTH_STYLE = {
  OPERATIONAL: { dot: 'bg-health-operational', text: 'text-health-operational-fg', label: 'Operational' },
  DEGRADED: { dot: 'bg-health-degraded', text: 'text-health-degraded-fg', label: 'Degraded' },
  MAJOR_OUTAGE: { dot: 'bg-health-down', text: 'text-health-down-fg', label: 'Major outage' },
} as const

const sectionTitle = 'text-[20px] leading-none font-[750]'
const condensed = { fontStretch: '75%' } as const

function StatusContent({ data }: { data: PublicStatus }) {
  const hasOutage = data.services.some((service) => service.health === 'MAJOR_OUTAGE')
  const hasDegradation = data.services.some((service) => service.health === 'DEGRADED')
  const summary = hasOutage
    ? 'Major outage'
    : hasDegradation
      ? 'Partial outage'
      : 'All systems operational'

  return (
    <>
      <div className="flex items-center gap-3.5 rounded-lg bg-sev-2-bg px-5 py-5 text-sev-2-fg">
        <TriangleAlert className="size-[22px] shrink-0" aria-hidden="true" />
        <div className="flex flex-col gap-0.5">
          <h1 className="text-[24px] leading-[1.1] font-[750]" style={condensed}>
            {summary}
          </h1>
          <span className="text-[13px]">{data.orgName} service status</span>
        </div>
      </div>

      <section className="flex flex-col gap-3.5">
        <h2 className={sectionTitle} style={condensed}>Active incidents</h2>
        {data.activeIncidents.length === 0 ? (
          <p className="rounded-lg border bg-card px-5 py-4 text-muted-foreground">
            No active incidents.
          </p>
        ) : (
          data.activeIncidents.map((incident) => (
            <article key={incident.id} className="flex flex-col rounded-lg border bg-card">
              <div className="border-b px-5 py-4">
                <span className="text-base font-semibold">{incident.title}</span>
              </div>
              <ol className="px-5 py-2">
                {incident.updates.map((update, index) => (
                  <li key={index} className="border-b py-3 last:border-b-0">
                    <div className="flex justify-between gap-3">
                      <span className="font-semibold">{update.status}</span>
                      <time className="font-mono text-xs text-muted-foreground">
                        {new Date(update.createdAt).toLocaleString()}
                      </time>
                    </div>
                    <p className="mt-1">{update.message}</p>
                  </li>
                ))}
              </ol>
            </article>
          ))
        )}
      </section>

      <section className="flex flex-col gap-3.5">
        <div className="flex items-baseline justify-between gap-3">
          <h2 className={sectionTitle} style={condensed}>Components</h2>
          <span className="text-[12.5px] text-muted-foreground">Last 90 days</span>
        </div>
        <div className="overflow-hidden rounded-lg border bg-card">
          {data.services.length === 0 ? (
            <p className="px-5 py-4 text-muted-foreground">No public services available.</p>
          ) : (
            data.services.map((service, index) => {
              const style = HEALTH_STYLE[service.health]
              return (
                <div key={service.id} className={cn('flex flex-col gap-2.5 px-5 py-4', index > 0 && 'border-t')}>
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-semibold">{service.name}</span>
                    <span className={cn('inline-flex items-center gap-[7px] text-[13px] font-semibold', style.text)}>
                      <span className={cn('size-2 rounded-full', style.dot)} aria-hidden="true" />
                      {style.label}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    90-day uptime: {service.uptimePercent.toFixed(2)}%
                  </p>
                </div>
              )
            })
          )}
        </div>
      </section>

      <section className="flex flex-col gap-3.5">
        <h2 className={sectionTitle} style={condensed}>Past incidents</h2>
        <div className="overflow-hidden rounded-lg border bg-card">
          {data.resolvedIncidents.length === 0 ? (
            <p className="px-5 py-4 text-muted-foreground">No resolved incidents in the last 90 days.</p>
          ) : (
            data.resolvedIncidents.map((incident, index) => (
              <div key={incident.id} className={cn('flex flex-col gap-1 px-5 py-3.5', index > 0 && 'border-t')}>
                <span className="font-semibold">{incident.title}</span>
                <time className="text-xs text-muted-foreground">
                  Resolved {new Date(incident.resolvedAt).toLocaleString()}
                </time>
              </div>
            ))
          )}
        </div>
      </section>
    </>
  )
}

/** Public page: no login required. */
export function StatusPage() {
  const { slug } = useParams<{ slug: string }>()
  const status = useQuery({
    queryKey: ['public-status', slug],
    queryFn: () => getPublicStatus(slug!),
    enabled: Boolean(slug),
    retry: false,
  })

  const notFound = !slug || (
    status.error instanceof ApiRequestError && status.error.status === 404
  )

  return (
    <div className="min-h-screen bg-background text-[14px] text-foreground">
      <div className="mx-auto flex max-w-[880px] flex-col gap-8 px-6 pt-10 pb-16">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-baseline gap-2.5">
            <span className="text-[30px] leading-none font-extrabold" style={{ fontStretch: '70%' }}>
              settle
            </span>
            <span className="text-[15px] font-semibold text-muted-foreground">status</span>
          </div>
          <ThemeToggle />
        </header>

        {notFound ? (
          <div role="alert">
            <h1 className="text-2xl font-bold">Status page not found</h1>
            <p className="mt-2 text-muted-foreground">Check the organization link and try again.</p>
          </div>
        ) : status.isPending ? (
          <p role="status">Loading public status...</p>
        ) : status.isError ? (
          <div role="alert">
            <h1 className="text-2xl font-bold">Unable to load status</h1>
            <p>{status.error.message}</p>
            <button type="button" className="mt-3 underline" onClick={() => status.refetch()}>
              Try again
            </button>
          </div>
        ) : (
          <StatusContent data={status.data} />
        )}

        <footer className="flex flex-wrap justify-between gap-3 pt-2 text-[12.5px] text-muted-foreground">
          <span>Public service health and incident updates.</span>
          <Link to="/" className="font-semibold">Powered by Settle</Link>
        </footer>
      </div>
    </div>
  )
}
