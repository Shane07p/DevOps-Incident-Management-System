import { ArrowRight, Check, Sparkles } from 'lucide-react'
import { motion } from 'motion/react'
import { Link } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { Container, Initials } from './parts'

const FACTS = [
  { term: 'Severity', value: 'SEV1 to SEV4' },
  { term: 'Paged when', value: '2 locations fail twice' },
  { term: 'Times', value: 'Shown in IST' },
]

const NOTES = [
  { key: 'paged', title: 'Settle paged you', body: 'INC-1043 · SEV1 · UPI payment authorisations failing', time: 'now' },
  { key: 'lead', title: 'Rohan Shah is Lead on INC-1041', body: 'Response target met in 5 minutes', time: '4m' },
  { key: 'resolved', title: 'INC-1039 resolved', body: 'Search indexing lag, inside its 24h target', time: '12m' },
]

export function Hero() {
  return (
    <section id="top" aria-labelledby="hero-heading" className="pt-[104px] pb-20 sm:pt-[120px]">
      <Container className="grid items-center gap-12 lg:grid-cols-12">
        <div className="flex flex-col gap-7 lg:col-span-5">
          <h1
            id="hero-heading"
            className="text-[clamp(2.75rem,5.5vw+0.5rem,4.75rem)] leading-[0.98] font-medium tracking-[-0.04em] text-balance"
          >
            Incident management for on-call teams
          </h1>
          <p className="max-w-[34rem] text-lg leading-relaxed text-foreground/90 sm:text-xl">
            Alerts from Grafana and your own uptime checks open one incident, page whoever is on call and start two SLA clocks.
            The first person to acknowledge becomes Lead.
          </p>
          <div className="flex flex-wrap gap-3">
            <Link
              to="/signup"
              className="inline-flex h-12 items-center gap-2 rounded-md bg-primary px-6 text-base font-medium text-primary-foreground hover:bg-primary/90 hover:no-underline"
            >
              Create a workspace
              <ArrowRight aria-hidden="true" className="size-4" />
            </Link>
            <a
              href="#flow"
              className="inline-flex h-12 items-center rounded-md border border-border px-6 text-base font-medium text-foreground hover:bg-secondary hover:no-underline"
            >
              See the incident flow
            </a>
          </div>
          <dl className="grid grid-cols-3 gap-4 pt-2">
            {FACTS.map((fact) => (
              <div key={fact.term} className="flex flex-col gap-1.5">
                <dt className="text-sm text-muted-foreground">{fact.term}</dt>
                <dd className="text-[15px] font-medium">{fact.value}</dd>
              </div>
            ))}
          </dl>
        </div>

        <div className="lg:col-span-6 lg:col-start-7">
          <HeroPicture />
        </div>
      </Container>
    </section>
  )
}

/** Fluted glass on the sand ground, with Settle's notifications on it. */
function HeroPicture() {
  return (
    <div
      role="img"
      aria-label="Settle's notifications: you are paged for INC-1043, Rohan Shah is Lead on INC-1041, and INC-1039 is resolved. INC-1043's response clock has 3 minutes left."
      className={cn(
        'relative isolate aspect-[7/8] overflow-hidden rounded-2xl bg-ground sm:aspect-[6/7]',
        '[background-image:repeating-linear-gradient(90deg,color-mix(in_oklab,var(--ground-surface)_75%,white)_0_10px,color-mix(in_oklab,var(--ground)_88%,var(--ground-accent))_24px,color-mix(in_oklab,var(--ground-surface)_75%,white)_36px),radial-gradient(60%_50%_at_50%_45%,color-mix(in_oklab,var(--ground-accent)_40%,transparent),transparent)]',
      )}
    >
      <div aria-hidden="true" className="absolute inset-0 bg-[radial-gradient(70%_60%_at_45%_40%,transparent,color-mix(in_oklab,var(--ground)_55%,transparent))]" />
      <ul className="absolute inset-x-[10%] top-[23%] flex flex-col gap-2">
        {NOTES.map((note, index) => (
          <motion.li
            key={note.key}
            initial={{ opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.45, delay: 0.25 + index * 0.15, ease: [0.2, 0.8, 0.2, 1] }}
            className="flex items-center gap-3 rounded-lg border border-border bg-card/95 px-3.5 py-3 text-card-foreground shadow-lg"
          >
            {note.key === 'paged' && (
              <span className="grid size-7 shrink-0 place-items-center rounded-full bg-foreground text-background" aria-hidden="true">
                <Sparkles className="size-3.5" />
              </span>
            )}
            {note.key === 'lead' && <Initials className="size-7">RS</Initials>}
            {note.key === 'resolved' && (
              <span className="grid size-7 shrink-0 place-items-center rounded-full border border-border" aria-hidden="true">
                <Check className="size-3.5" />
              </span>
            )}
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="flex items-baseline justify-between gap-2 text-[13px] font-semibold">
                {note.title}
                {note.key !== 'paged' && <span className="font-normal text-muted-foreground">{note.time}</span>}
              </span>
              <span className="truncate text-xs text-muted-foreground">{note.body}</span>
            </span>
            {note.key === 'paged' && (
              <span className="shrink-0 rounded bg-foreground px-2 py-1 text-xs font-medium text-background">Acknowledge</span>
            )}
          </motion.li>
        ))}
      </ul>
      <motion.div
        initial={{ opacity: 0, y: 14 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, delay: 0.8, ease: [0.2, 0.8, 0.2, 1] }}
        className="absolute bottom-[4%] left-[4%] hidden w-[48%] flex-col gap-2 rounded-lg border border-border bg-card p-4 text-card-foreground shadow-xl sm:flex"
      >
        <span className="flex items-center justify-between text-[13px]">
          <span className="flex items-center gap-1.5">
            <span className="size-1.5 rounded-full bg-sla-at-risk" aria-hidden="true" />
            Response clock
          </span>
          <span className="text-muted-foreground">3m left</span>
        </span>
        <span className="text-base leading-snug font-medium">INC-1043, waiting for acknowledgement</span>
      </motion.div>
    </div>
  )
}
