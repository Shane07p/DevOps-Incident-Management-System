import { Check, Minus } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { Container, Ground, Heading, MockCard, Reveal, Section } from './parts'

const STEPS = [
  {
    title: 'Acknowledge and take Lead',
    text: 'One click stops the response clock and the escalation. The resolution clock keeps running until the incident is resolved.',
    label:
      "Settle's list of incidents waiting for acknowledgement. INC-1043, SEV1, has 3 minutes left on its response clock and an Acknowledge and take Lead button.",
    picture: <AcknowledgeCard />,
  },
  {
    title: "Read the incident's timeline and evidence",
    text: 'Deploys, metrics and alerts for that service and time window sit on the incident. The AI suggests a cause and shows what it used.',
    label:
      "An incident in Settle: the response target met in 7 minutes, 3 hours 36 minutes left to resolve, and the AI's suggested cause with its evidence.",
    picture: (
      <div className="grid w-full max-w-[500px] gap-3 sm:grid-cols-[1fr_1.35fr]">
        <SlaCard response="7m" responseNote="Acknowledged in 7m · target 1h" resolution="3h 36m left" resolutionNote="Resolve by 18:02 IST" />
        <AiCard />
      </div>
    ),
  },
  {
    title: 'Resolve the incident with notes',
    text: 'Resolving asks what was verified. SEV1 and SEV2 incidents close only after a reviewed postmortem.',
    label: 'The Resolve incident dialog in Settle asking for recovery notes before the resolution clock stops.',
    picture: <ResolveCard />,
  },
]

export function FlowSection() {
  return (
    <Section id="flow" className="bg-card/40">
      <Container>
        <Heading id="flow">How an incident moves through Settle</Heading>
        <ol className="mt-12 flex flex-col">
          {STEPS.map((step) => (
            <li key={step.title} className="grid items-center gap-8 border-t border-border py-8 lg:grid-cols-2 lg:gap-12">
              <div className="flex max-w-[30rem] flex-col gap-3">
                <h3 className="text-xl font-medium tracking-[-0.01em]">{step.title}</h3>
                <p className="leading-relaxed text-foreground/85">{step.text}</p>
              </div>
              <Reveal>
                <Ground label={step.label} className="py-10 sm:aspect-[4/3] sm:py-10">
                  {step.picture}
                </Ground>
              </Reveal>
            </li>
          ))}
        </ol>
      </Container>
    </Section>
  )
}

export function Pill({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cn('inline-flex shrink-0 items-center rounded-md px-2.5 py-1 text-xs font-medium', className)}>{children}</span>
}

function AcknowledgeCard() {
  const rows = [
    { sev: 'SEV1', title: 'UPI payment authorisations failing', meta: 'INC-1043 · Payments ·', paging: 'Paging Rohan Shah', clock: '3m left', risk: true },
    { sev: 'SEV3', title: 'Order queue backlog above threshold', meta: 'INC-1040 · Order Fulfillment ·', paging: 'Paging Dev Mehta', clock: '3h 48m left' },
  ]
  return (
    <MockCard className="w-full max-w-[560px]">
      <div className="flex items-center gap-2 border-b border-border px-4 py-3">
        <b className="font-semibold">Waiting for acknowledgement</b>
        <span className="text-muted-foreground">2</span>
      </div>
      {rows.map((row) => (
        <div key={row.title} className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-3 border-b border-border px-4 py-3 last:border-0 sm:grid-cols-[auto_minmax(0,1fr)_auto]">
          <Pill className={row.sev === 'SEV1' ? 'bg-sev-1-bg text-sev-1-fg' : 'bg-sev-3-bg text-sev-3-fg'}>{row.sev}</Pill>
          <span className="flex min-w-0 flex-1 flex-col">
            <b className="truncate font-medium">{row.title}</b>
            <span className="truncate text-xs text-muted-foreground">
              {row.meta} <em className="text-sla-at-risk-fg not-italic">{row.paging}</em>
            </span>
          </span>
          <span className="col-span-2 flex flex-wrap items-center justify-between gap-1.5 sm:col-span-1 sm:flex-col sm:items-end">
            <span className={cn('inline-flex items-center gap-1.5 text-xs', row.risk ? 'text-sla-at-risk-fg' : 'text-muted-foreground')}>
              {row.risk && <i className="size-1.5 rounded-full bg-sla-at-risk" aria-hidden="true" />}
              {row.risk ? 'At risk' : 'Response'}
              <code className="font-mono">{row.clock}</code>
            </span>
            <Pill className="bg-primary text-primary-foreground">Acknowledge and take Lead</Pill>
          </span>
        </div>
      ))}
    </MockCard>
  )
}

/** Both clocks as the incident page shows them. Also used, larger, beside the questions. */
export function SlaCard({
  response,
  responseNote,
  resolution,
  resolutionNote,
  breached = false,
  footer,
  large = false,
}: {
  response: string
  responseNote: string
  resolution: string
  resolutionNote: string
  breached?: boolean
  footer?: string
  large?: boolean
}) {
  return (
    <MockCard className={cn('flex flex-col', large ? 'w-full max-w-[448px] p-5' : 'p-4')}>
      <b className={cn('border-b border-border pb-3 font-semibold', large && 'text-sm')}>Service-level targets</b>
      <div className="flex flex-col gap-2 border-b border-border py-4">
        <span className="flex items-center justify-between text-muted-foreground">
          Response
          <Pill className="border border-sla-met/40 px-1.5 py-0.5 text-sla-met">Met · stopped</Pill>
        </span>
        <code className={cn('font-mono font-medium', large ? 'text-3xl' : 'text-xl')}>{response}</code>
        <i className="h-1 rounded-full bg-sla-track" aria-hidden="true">
          <u className="block h-full w-[6%] rounded-full bg-sla-met" />
        </i>
        <small className="text-xs text-muted-foreground">{responseNote}</small>
      </div>
      <div className="flex flex-col gap-2 pt-4">
        <span className="flex items-center justify-between text-muted-foreground">
          Resolution
          <Pill className={cn('border px-1.5 py-0.5', breached ? 'border-sla-breached/50 text-sla-breached' : 'border-border text-foreground')}>
            {breached ? 'Breached' : 'Running'}
          </Pill>
        </span>
        <code className={cn('font-mono font-medium', large ? 'text-3xl' : 'text-xl', breached && 'text-sla-breached')}>{resolution}</code>
        <i className="h-1 rounded-full bg-sla-track" aria-hidden="true">
          <u className={cn('block h-full rounded-full', breached ? 'w-full bg-sla-breached' : 'w-[10%] bg-muted-foreground')} />
        </i>
        <small className="text-xs text-muted-foreground">{resolutionNote}</small>
      </div>
      {footer && <p className="mt-4 border-t border-border pt-4 text-muted-foreground">{footer}</p>}
    </MockCard>
  )
}

function AiCard() {
  return (
    <MockCard className="flex flex-col gap-3 p-4">
      <Pill className="self-start border border-primary/40 px-1.5 py-0.5 text-primary">AI suggestion</Pill>
      <p className="leading-snug">Connection pool saturation may explain the checkout failures.</p>
      <span className="text-xs text-muted-foreground">Supporting evidence</span>
      <ul className="flex flex-col gap-1.5 text-xs">
        <li className="flex gap-1.5">
          <Check aria-hidden="true" className="mt-px size-3.5 shrink-0 text-sla-met" />
          Deploy a8f3c21 lowered the pool from 40 to 20
        </li>
        <li className="flex gap-1.5">
          <Check aria-hidden="true" className="mt-px size-3.5 shrink-0 text-sla-met" />
          INC-1038 started the same way
        </li>
      </ul>
      <span className="text-xs text-muted-foreground">Still to verify</span>
      <p className="flex gap-1.5 text-xs">
        <Minus aria-hidden="true" className="mt-px size-3.5 shrink-0 text-muted-foreground" />
        No pool metrics captured yet
      </p>
      <span className="flex gap-2">
        <Pill className="border border-border">Accept</Pill>
        <Pill className="border border-border">Reject</Pill>
      </span>
    </MockCard>
  )
}

function ResolveCard() {
  return (
    <MockCard className="flex w-full max-w-[440px] flex-col gap-3 p-5">
      <b className="text-base font-semibold">Resolve incident</b>
      <p className="text-muted-foreground">Confirm that recovery has been verified. This stops the resolution clock.</p>
      <span className="mt-1 text-xs font-medium">Recovery notes</span>
      <div className="rounded-md border border-border bg-background px-3 py-2.5">Rolled back a8f3c21. Checkout errors under 1% for 15 minutes.</div>
      <small className="text-xs text-muted-foreground">SEV1 and SEV2 incidents close after a reviewed postmortem.</small>
      <span className="mt-1 flex justify-end gap-2">
        <Pill className="border border-border">Cancel</Pill>
        <Pill className="bg-primary text-primary-foreground">Resolve incident</Pill>
      </span>
    </MockCard>
  )
}
