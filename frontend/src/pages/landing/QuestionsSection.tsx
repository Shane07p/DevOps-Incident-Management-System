import { SlaCard } from './FlowSection'
import { Container, Ground, Heading, Reveal, Section } from './parts'

const QUESTIONS = [
  {
    q: 'Who becomes Lead on an incident?',
    a: 'Whoever acknowledges first. Escalation notifies the next person if nobody does, but never reassigns.',
  },
  {
    q: 'Does the AI change incidents?',
    a: "No. It suggests a cause and shows its evidence. Status, clocks and escalation follow Settle's own rules.",
  },
  {
    q: 'What happens when an incident misses its target?',
    a: 'The clock turns red and counts up past the target, which stays as it was when the incident opened.',
  },
  {
    q: 'Who can change an incident?',
    a: 'Admins configure, Engineers respond and Viewers read. The server checks every request.',
  },
]

export function QuestionsSection() {
  return (
    <Section id="questions">
      <Container className="grid items-start gap-12 lg:grid-cols-2 lg:gap-6">
        <div>
          <Heading id="questions">Questions on-call teams ask</Heading>
          <dl className="mt-10">
            {QUESTIONS.map((item) => (
              <div key={item.q} className="flex flex-col gap-2 border-b border-border py-6">
                <dt className="text-xl font-medium tracking-[-0.01em]">{item.q}</dt>
                <dd className="leading-relaxed text-foreground/85">{item.a}</dd>
              </div>
            ))}
          </dl>
        </div>
        <Reveal>
          <Ground
            label="An incident's SLA targets in Settle: response met in 5 minutes; resolution breached, 30 minutes over its 8 hour target."
            className="aspect-[3/2] lg:aspect-[5/6]"
          >
            <SlaCard
              large
              breached
              response="5m"
              responseNote="Acknowledged in 5m · target 4h"
              resolution="+30m"
              resolutionNote="over the 8h target"
              footer="Targets fixed when the incident opened"
            />
          </Ground>
        </Reveal>
      </Container>
    </Section>
  )
}
