import { Activity, CircleCheck, Globe, Server } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { Container, Ground, Heading, MockCard, Reveal, Section } from './parts'

export function MonitoringSection() {
  return (
    <Section id="monitoring">
      <Container>
        <Heading id="monitoring" className="max-w-[46rem]">
          Settle pages only when its checks agree. <span className="text-muted-foreground">One bad network path wakes nobody.</span>
        </Heading>
        <div className="mt-14 grid gap-10 lg:grid-cols-2 lg:gap-6">
          <article className="flex flex-col gap-3">
            <h3 className="text-lg font-medium">Checks each URL from several locations</h3>
            <p className="max-w-[34rem] leading-relaxed text-foreground/85">
              A service turns Down when two locations fail twice in a row, and Degraded when two are slow three times.
            </p>
            <Reveal className="mt-4">
              <Ground
                label="One uptime check runs from three locations, A, B and C, and Settle compares the results before paging anyone."
                className="aspect-[16/9] p-0 sm:p-0"
              >
                <UptimeDiagram />
              </Ground>
            </Reveal>
          </article>
          <article className="flex flex-col gap-3">
            <h3 className="text-lg font-medium">Tells customers what is down</h3>
            <p className="max-w-[34rem] leading-relaxed text-foreground/85">
              The public status page shows each service's 90-day uptime and the updates Comms posts. Internal notes never appear there.
            </p>
            <Reveal className="mt-4">
              <Ground
                label="Settle's public status page: some services degraded, with Checkout, Payments and Order processing degraded and Search operational."
                className="py-10 sm:aspect-[16/9] sm:py-10"
              >
                <StatusCard />
              </Ground>
            </Reveal>
          </article>
        </div>
      </Container>
    </Section>
  )
}

function Node({ icon, children, className, top }: { icon: ReactNode; children: ReactNode; className?: string; top?: string }) {
  return (
    <span
      style={top ? { top } : undefined}
      className={cn(
        'absolute flex -translate-y-1/2 items-center gap-1.5 rounded-lg border border-border bg-card px-2 py-1.5 text-[11px] whitespace-nowrap text-card-foreground shadow-md sm:gap-2 sm:px-2.5 sm:text-[13px]',
        className,
      )}
    >
      {icon}
      {children}
    </span>
  )
}

const LANES = [
  { label: 'A', meta: '200, 180 ms', ok: true, top: '22%' },
  { label: 'B', meta: '200, 210 ms', ok: true, top: '50%' },
  { label: 'C', meta: 'timeout', ok: false, top: '78%' },
]

/** URL → uptime check → three locations → Up; the lines are drawn in one SVG under the tiles. */
function UptimeDiagram() {
  const icon = 'size-3.5 shrink-0 sm:size-4'
  return (
    <div className="relative size-full">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true" className="absolute inset-0 size-full">
        <g fill="none" strokeWidth="1.5" vectorEffect="non-scaling-stroke" className="stroke-ground-accent/70">
          <path d="M18 50 H30" vectorEffect="non-scaling-stroke" />
          {LANES.map((lane) => {
            const y = Number.parseFloat(lane.top)
            return (
              <g key={lane.label}>
                <path d={`M44 50 C50 50 48 ${y} 54 ${y}`} vectorEffect="non-scaling-stroke" />
                {lane.ok && <path d={`M76 ${y} C82 ${y} 80 50 86 50`} vectorEffect="non-scaling-stroke" />}
              </g>
            )
          })}
        </g>
      </svg>
      <Node icon={<Globe aria-hidden="true" className={icon} />} className="top-1/2 left-[2%]">
        URL <code className="hidden font-mono text-muted-foreground sm:inline">every 30 s</code>
      </Node>
      <span className="absolute top-1/2 left-[30%] flex -translate-y-1/2 flex-col items-center gap-1 rounded-xl bg-ground-surface px-3 py-2.5 text-[11px] text-foreground shadow-lg sm:px-4 sm:py-3.5 sm:text-[13px] dark:text-background">
        <Activity aria-hidden="true" className="size-5 sm:size-6" />
        Uptime check
      </span>
      {LANES.map((lane) => (
        <Node key={lane.label} top={lane.top} icon={<Server aria-hidden="true" className={icon} />} className="left-[54%]">
          <b className="font-semibold">{lane.label}</b>
          <code className="hidden font-mono text-muted-foreground sm:inline">{lane.meta}</code>
          {lane.ok && <CircleCheck aria-hidden="true" className="size-3.5 shrink-0 text-primary sm:size-4" />}
        </Node>
      ))}
      <Node icon={<CircleCheck aria-hidden="true" className={icon} />} className="top-1/2 right-[2%]">
        Up
      </Node>
    </div>
  )
}

const SERVICES = [
  { name: 'Checkout', degraded: true },
  { name: 'Payments', degraded: true },
  { name: 'Search', degraded: false },
  { name: 'Order processing', degraded: true },
]

function StatusCard() {
  return (
    <MockCard className="w-full max-w-[400px] text-xs">
      <div className="flex items-start gap-2 border-b border-border px-4 py-3">
        <span className="mt-1 size-1.5 shrink-0 rounded-full bg-health-degraded" aria-hidden="true" />
        <span className="flex flex-col">
          <b className="text-[13px] font-semibold">Some services are degraded</b>
          <small className="text-muted-foreground">Updated 14:26 IST</small>
        </span>
      </div>
      {SERVICES.map((service) => (
        <div key={service.name} className="flex flex-col gap-2 border-b border-border px-4 py-2.5 last:border-0">
          <span className="flex justify-between">
            {service.name}
            <em className={cn('not-italic', service.degraded ? 'text-health-degraded' : 'text-health-operational')}>
              {service.degraded ? 'Degraded' : 'Operational'}
            </em>
          </span>
          <span className="flex gap-[2px]" aria-hidden="true">
            {Array.from({ length: 40 }, (_, day) => (
              <i
                key={day}
                className={cn('h-2 flex-1 rounded-[1px]', service.degraded && day >= 34 ? 'bg-health-degraded' : 'bg-health-operational/70')}
              />
            ))}
          </span>
        </div>
      ))}
    </MockCard>
  )
}
