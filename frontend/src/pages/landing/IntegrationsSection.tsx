import { Activity, HeartPulse, Siren, Webhook } from 'lucide-react'
import type { ReactNode } from 'react'
import githubActions from './logos/github-actions.svg'
import grafana from './logos/grafana.svg'
import opentelemetry from './logos/opentelemetry.svg'
import prometheus from './logos/prometheus.svg'
import { Container, Ground, Heading, Reveal, Section } from './parts'

type Source = { label: string; sub: string; logo?: string; icon?: ReactNode }

const SOURCES: Source[] = [
  { label: 'Grafana', sub: 'Alert rules', logo: grafana },
  { label: 'Prometheus', sub: 'Metrics', logo: prometheus },
  { label: 'OpenTelemetry', sub: 'Logs and traces', logo: opentelemetry },
  { label: 'GitHub Actions', sub: 'Deploys', logo: githubActions },
  { label: 'Uptime checks', sub: 'Settle', icon: <Activity className="size-4" /> },
  { label: 'Heartbeats', sub: 'Settle', icon: <HeartPulse className="size-4" /> },
]

export function IntegrationsSection() {
  return (
    <Section id="tools">
      <Container>
        <Heading id="tools" className="max-w-[46rem]">
          Works with Grafana, Prometheus and OpenTelemetry
        </Heading>
        <p className="mt-6 max-w-[46rem] text-lg leading-relaxed text-foreground/90">
          Each tool posts to one webhook with its own token. Repeated events count once, and related alerts join the open incident
          by correlation key.
        </p>
        <Reveal className="mt-12">
          <Ground
            label="Grafana, Prometheus, OpenTelemetry, GitHub Actions and Settle's own checks send alerts to one webhook, which opens or updates one incident."
            className="py-10 sm:py-14"
          >
            <FanIn />
          </Ground>
        </Reveal>
      </Container>
    </Section>
  )
}

/** Six sources fan into the webhook, which leads to one incident; the lines are one SVG behind the tiles. */
function FanIn() {
  const rowY = (index: number) => 8 + index * (84 / (SOURCES.length - 1))
  return (
    <div className="relative grid w-full max-w-[860px] grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)_minmax(0,1fr)] items-center gap-4">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true" className="absolute inset-0 size-full">
        <g fill="none" strokeWidth="1.5" className="stroke-primary/45">
          {SOURCES.map((source, index) => (
            <path key={source.label} d={`M36 ${rowY(index)} C46 ${rowY(index)} 42 50 52 50`} vectorEffect="non-scaling-stroke" />
          ))}
          <path d="M64 50 H74" vectorEffect="non-scaling-stroke" />
        </g>
      </svg>
      <ul className="relative flex flex-col items-end gap-2.5">
        {SOURCES.map((source) => (
          <li key={source.label} className="flex w-full max-w-[180px] items-center gap-2.5 rounded-lg border border-border bg-card px-2.5 py-2 text-card-foreground shadow-md">
            <span className="grid size-7 shrink-0 place-items-center rounded-md bg-secondary" aria-hidden="true">
              {source.logo ? <img src={source.logo} alt="" className="size-4" /> : source.icon}
            </span>
            <span className="flex min-w-0 flex-col leading-tight">
              <b className="truncate text-[13px] font-semibold">{source.label}</b>
              <small className="truncate text-[11px] text-muted-foreground">{source.sub}</small>
            </span>
          </li>
        ))}
      </ul>
      <span className="relative mx-auto flex flex-col items-center gap-1.5 rounded-xl bg-ground-surface px-4 py-3.5 text-[13px] text-background shadow-lg">
        <Webhook aria-hidden="true" className="size-6" />
        Alert webhook
      </span>
      <span className="relative flex items-center gap-2 justify-self-start rounded-lg border border-border bg-card px-3 py-2 text-[13px] text-card-foreground shadow-md">
        <Siren aria-hidden="true" className="size-4" />
        <b className="font-semibold">One incident</b>
        <code className="hidden font-mono text-xs text-muted-foreground sm:inline">deduplicated</code>
      </span>
    </div>
  )
}
