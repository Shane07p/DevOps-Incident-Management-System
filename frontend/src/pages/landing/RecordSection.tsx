import { motion } from 'motion/react'
import { Container, Section } from './parts'

const ROWS = [
  { name: 'Targets', detail: 'Copied from the service when the incident opens', value: 'Fixed' },
  { name: 'Timeline', detail: 'Alerts, status changes, notes, deploys and AI findings', value: 'Append-only' },
  { name: 'SEV1 and SEV2', detail: 'A reviewed postmortem before the incident closes', value: 'Required' },
  { name: 'Every change', detail: 'Attributed to a person, with the time in IST', value: 'Signed' },
  { name: 'Corrections', detail: 'Added as amendments linked to the entry they correct', value: 'Linked' },
]

/** "Every incident, settled": what the record keeps, row by row. */
export function RecordSection() {
  return (
    <Section id="settled">
      <Container>
        <h2
          id="settled-heading"
          className="text-[clamp(3.5rem,10vw,8.5rem)] leading-[0.92] font-medium tracking-[-0.045em] text-balance"
        >
          Every incident, settled
        </h2>
        <p className="mt-8 max-w-[46rem] text-lg leading-relaxed text-foreground/90 sm:text-xl">
          SLA targets are copied when an incident opens and never change, so the record shows what was promised and what happened.
        </p>
        <ul className="mt-12 border-t border-border">
          {ROWS.map((row, index) => (
            <motion.li
              key={row.name}
              initial={{ opacity: 0, x: -12 }}
              whileInView={{ opacity: 1, x: 0 }}
              viewport={{ once: true, margin: '0px 0px -60px 0px' }}
              transition={{ duration: 0.4, delay: index * 0.06, ease: [0.2, 0.8, 0.2, 1] }}
              className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 border-b border-border py-4"
            >
              <span className="flex flex-wrap items-baseline gap-x-3">
                <b className="font-medium">{row.name}</b>
                <small className="text-sm text-muted-foreground">{row.detail}</small>
              </span>
              <span className="font-medium">{row.value}</span>
            </motion.li>
          ))}
        </ul>
      </Container>
    </Section>
  )
}
