import { FileText, GitBranch, Inbox, Search, Sparkles } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { Container, Heading, Initials, Reveal, Section } from './parts'

type Row = { who: string; initials: string; incident: string; status: string; clock: string; waiting?: boolean; done?: boolean }

const ROWS: Row[] = [
  { who: 'Paging Rohan Shah', initials: 'RS', incident: 'INC-1043 · UPI authorisations failing', status: 'Waiting', clock: '3m', waiting: true },
  { who: 'Paging Dev Mehta', initials: 'DM', incident: 'INC-1040 · Order queue backlog', status: 'Waiting', clock: '3h 48m', waiting: true },
  { who: 'Paging Kavya Iyer', initials: 'KI', incident: 'INC-1045 · Search results empty', status: 'Waiting', clock: '54m', waiting: true },
  { who: 'Rohan Shah', initials: 'RS', incident: 'INC-1041 · Gateway latency, 30m over', status: 'Monitoring', clock: '+30m' },
  { who: 'Asha Patel', initials: 'AP', incident: 'INC-1042 · Checkout failures after deploy', status: 'Investigating', clock: '3h 36m' },
  { who: 'Dev Mehta', initials: 'DM', incident: 'INC-1036 · Invoice job missed heartbeat', status: 'Identified', clock: '5h 10m' },
  { who: 'Kavya Iyer', initials: 'KI', incident: 'INC-1035 · Cache hit rate dropped', status: 'Monitoring', clock: '6h 02m' },
  { who: 'Asha Patel', initials: 'AP', incident: 'INC-1033 · TLS certificate expires in 9 days', status: 'Identified', clock: '2d 4h' },
  { who: 'Rohan Shah', initials: 'RS', incident: 'INC-1039 · Search indexing lag', status: 'Resolved', clock: '42m', done: true },
]

const SIDE = [
  { label: 'Active', count: '9', on: true },
  { label: 'Mine', count: '2' },
  { label: 'Waiting', count: '3' },
  { label: 'Resolved' },
]

export function ProductSection() {
  return (
    <Section id="product" className="bg-card/40">
      <Container>
        <div className="grid items-start gap-6 lg:grid-cols-12">
          <Heading id="product" className="lg:col-span-5">
            Every incident and its timeline
          </Heading>
          <p className="text-lg leading-relaxed text-muted-foreground lg:col-span-6 lg:col-start-7">
            The list sorts by urgency: waiting for acknowledgement first, then the clock closest to its target. Open an incident
            to read its alerts, deploys, notes and the AI's suggestion in order.
          </p>
        </div>

        <Reveal className="mt-14">
          <div
            role="img"
            aria-label="Settle with the open incidents listed on the left and the timeline of INC-1042 open on the right"
            className="grid h-[560px] overflow-hidden rounded-xl border border-border bg-card text-card-foreground shadow-2xl md:grid-cols-[5fr_7fr]"
          >
            <IncidentList />
            <Timeline />
          </div>
        </Reveal>
      </Container>
    </Section>
  )
}

function IncidentList() {
  return (
    <div className="relative hidden min-h-0 flex-col border-r border-border md:flex">
      <div className="flex items-center gap-3 border-b border-border px-4 py-3 text-[13px]">
        <span className="flex gap-1.5" aria-hidden="true">
          <i className="size-2.5 rounded-full bg-secondary" />
          <i className="size-2.5 rounded-full bg-secondary" />
          <i className="size-2.5 rounded-full bg-secondary" />
        </span>
        <span className="text-muted-foreground">
          Incidents <span className="px-1">/</span> <b className="font-medium text-foreground">Active</b>
        </span>
        <span className="ml-auto flex items-center gap-2 rounded-md border border-border px-2 py-1 text-muted-foreground">
          <Search className="size-3.5" aria-hidden="true" />
          Search
          <kbd className="rounded border border-border px-1 font-mono text-[11px]">/</kbd>
        </span>
      </div>
      <div className="grid min-h-0 flex-1 grid-cols-[120px_1fr] xl:grid-cols-[136px_1fr]">
        <div className="hidden flex-col gap-0.5 p-2 text-[13px] lg:flex">
          {SIDE.map((item) => (
            <span
              key={item.label}
              className={cn('flex items-center gap-2 rounded-md px-2 py-1.5 text-muted-foreground', item.on && 'bg-secondary font-medium text-foreground')}
            >
              {item.label === 'Active' && <Inbox className="size-3.5" aria-hidden="true" />}
              {item.label}
              {item.count && <em className="ml-auto text-xs not-italic">{item.count}</em>}
            </span>
          ))}
          <span className="mt-3 px-2 text-xs text-muted-foreground">Services</span>
          <span className="px-2 py-1.5 text-muted-foreground"># Payments</span>
          <span className="px-2 py-1.5 text-muted-foreground"># Checkout</span>
        </div>
        <ul className="col-span-2 min-h-0 overflow-hidden lg:col-span-1">
          {ROWS.map((row) => (
            <li key={row.incident} className={cn('flex items-center gap-3 border-b border-border px-3 py-2.5', row.done && 'opacity-50')}>
              <Initials>{row.initials}</Initials>
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[13px] font-medium">{row.who}</span>
                <span className="truncate text-xs text-muted-foreground">{row.incident}</span>
              </span>
              <span
                className={cn(
                  'inline-flex shrink-0 items-center gap-1 rounded-md border border-border px-1.5 py-0.5 text-[11px]',
                  row.waiting ? 'border-transparent bg-foreground text-background' : 'text-muted-foreground',
                )}
              >
                <span className={cn('size-1 rounded-full', row.waiting ? 'bg-background' : 'bg-muted-foreground')} aria-hidden="true" />
                {row.status}
              </span>
            </li>
          ))}
        </ul>
      </div>
      <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-b from-transparent to-card" />
    </div>
  )
}

function Bot({ children }: { children: ReactNode }) {
  return (
    <div className="flex max-w-[78%] gap-3">
      <span className="grid size-6 shrink-0 place-items-center rounded-full bg-foreground text-background" aria-hidden="true">
        <Sparkles className="size-3" />
      </span>
      <span className="flex flex-col gap-2 leading-relaxed">{children}</span>
    </div>
  )
}

function Me({ children }: { children: ReactNode }) {
  return <div className="ml-auto max-w-[62%] rounded-lg border border-border bg-secondary px-3 py-2 leading-relaxed">{children}</div>
}

function Timeline() {
  return (
    <div className="relative flex min-h-0 flex-col text-[13px]">
      <div className="flex items-center gap-2 border-b border-border px-4 py-3">
        <span className="grid size-5 place-items-center rounded-full bg-foreground text-background" aria-hidden="true">
          <Sparkles className="size-2.5" />
        </span>
        <b className="font-medium">INC-1042 · Checkout failures</b>
        <span className="flex items-center gap-1 text-muted-foreground">
          <span className="size-1 rounded-full bg-muted-foreground" aria-hidden="true" />
          Investigating
        </span>
      </div>
      <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-hidden px-4 py-4">
        <p className="text-center text-xs text-muted-foreground">31 Oct · IST · response met in 7m · 3h 36m left to resolve</p>
        <Bot>
          <span>
            14:02 · Prometheus: checkout 5xx above 5% for 3 minutes. <b className="font-semibold">18 alerts</b> share this
            incident's correlation key.
          </span>
        </Bot>
        <Me>14:09 · Acknowledged. I'm Lead. Escalation stopped.</Me>
        <Bot>
          <span>14:00 · Deploy a8f3c21 to checkout-api by Dev Mehta, two minutes before the first alert.</span>
        </Bot>
        <Me>The spike starts two minutes after deploy a8f3c21. Checking the connection pool changes against the last healthy release.</Me>
        <Bot>
          <span>
            Connection pool saturation may explain the failures. <b className="font-semibold">a8f3c21 lowered the pool from 40 to 20</b>,
            and INC-1038 began the same way.
          </span>
          <span className="flex gap-2">
            <span className="inline-flex items-center gap-1 rounded border border-border px-2 py-0.5 text-xs">
              <GitBranch className="size-3" aria-hidden="true" /> Deploy a8f3c21
            </span>
            <span className="inline-flex items-center gap-1 rounded border border-border px-2 py-0.5 text-xs">
              <FileText className="size-3" aria-hidden="true" /> INC-1038
            </span>
          </span>
        </Bot>
        <Me>Rolling back a8f3c21 now. Error rate back under 1% at 14:31.</Me>
      </div>
      <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-b from-transparent to-card" />
    </div>
  )
}
