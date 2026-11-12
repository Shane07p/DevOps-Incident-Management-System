import { CircleCheck, TriangleAlert } from 'lucide-react'
import { Link } from 'react-router-dom'
import { UptimeBars } from '@/components/app/UptimeBars'
import type { DayState } from '@/lib/statusTypes'
import { cn } from '@/lib/utils'

const SERVICES: { name: string; health: 'Operational' | 'Degraded'; events: Record<number, Exclude<DayState, 'ok'>> }[] = [
  { name: 'Payments', health: 'Operational', events: {} },
  { name: 'Checkout API', health: 'Degraded', events: { 89: 'degraded', 77: 'down', 52: 'degraded' } },
  { name: 'Authentication', health: 'Operational', events: { 75: 'degraded' } },
  { name: 'Search', health: 'Operational', events: { 88: 'degraded', 60: 'degraded' } },
  { name: 'Notifications', health: 'Operational', events: { 86: 'degraded' } },
]

export function StatusPreview() {
  return (
    <section id="status" className="relative isolate scroll-mt-24 overflow-hidden border-y bg-muted">
      <div
        aria-hidden="true"
        className="absolute -top-[120px] -right-[200px] -z-10 size-[760px] rounded-full [background:radial-gradient(circle,color-mix(in_oklch,var(--primary)_22%,transparent),transparent_65%)]"
      />
      <div className="mx-auto flex max-w-(--page-max) flex-wrap items-start gap-14 px-4 py-28 sm:px-8">
        <div className="flex min-w-0 flex-[1_1_340px] flex-col gap-[18px]">
          <h2 className="text-[clamp(48px,5.8vw,84px)] leading-[0.9] font-[750] tracking-[-0.01em]" style={{ fontStretch: '62%' }}>
            Tell customers before they ask.
          </h2>
          <p className="text-[17px] text-pretty text-muted-foreground">
            A public status page with health from automated checks, 90 days of history, and the updates your responders post. Hover the bars.
          </p>
          <div className="flex flex-wrap gap-4 font-semibold">
            <Link to="/status">See the status page</Link>
            <Link to="/login">Log in to manage it</Link>
          </div>
        </div>
        <div className="min-w-0 flex-[1.7_1_520px] overflow-hidden rounded-2xl border border-foreground/10 bg-card/72 shadow-elev-3 backdrop-blur-[20px] backdrop-saturate-[1.3]">
          <div className="flex items-center gap-2.5 border-b px-4 py-[11px] font-mono text-xs font-medium text-muted-foreground">
            <span className="flex gap-[5px]" aria-hidden="true">
              {[0, 1, 2].map((dot) => (
                <span key={dot} className="size-[9px] rounded-full bg-rail" />
              ))}
            </span>
            <span className="mx-auto rounded-md bg-muted px-3 py-0.5">status.settle.app</span>
          </div>
          <div className="flex flex-col gap-[18px] px-[22px] py-5">
            <div className="flex items-center gap-3 rounded-xl bg-sev-3-bg px-4 py-3.5 text-health-degraded-fg">
              <TriangleAlert className="size-5" aria-hidden="true" />
              <span className="flex flex-col">
                <span className="text-base font-bold">Some services are degraded</span>
                <span className="font-mono text-xs font-medium">Updated 14 Oct, 12:22 IST</span>
              </span>
            </div>
            {SERVICES.map((service) => (
              <div key={service.name} className="flex flex-col gap-1.5">
                <div className="flex items-center justify-between gap-2.5">
                  <span className="font-semibold">{service.name}</span>
                  <span
                    className={cn(
                      'inline-flex items-center gap-1.5 text-[13.5px] font-semibold',
                      service.health === 'Operational' ? 'text-health-operational-fg' : 'text-health-degraded-fg',
                    )}
                  >
                    {service.health === 'Operational' ? <CircleCheck className="size-3.5" aria-hidden="true" /> : <TriangleAlert className="size-3.5" aria-hidden="true" />}
                    {service.health}
                  </span>
                </div>
                <UptimeBars name={service.name} events={service.events} />
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
