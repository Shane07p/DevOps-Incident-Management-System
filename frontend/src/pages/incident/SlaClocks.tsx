import type { Incident } from '@/api/incidents'
import { SlaClock } from '@/components/app/SlaClock'
import { useNow } from '@/lib/useNow'

/** Both SLA clocks, ticking each second. Kept apart from the page so only this part re-renders. */
export function SlaClocks({ incident, className = 'grid gap-4 sm:grid-cols-2' }: { incident: Incident; className?: string }) {
  const now = useNow()
  return (
    <div className={className}>
      <SlaClock
        label="Response"
        dueAt={incident.responseDueAt}
        stoppedAt={incident.acknowledgedAt}
        targetMinutes={incident.responseTargetMinutes}
        now={now}
      />
      <SlaClock
        label="Resolution"
        dueAt={incident.resolutionDueAt}
        stoppedAt={incident.resolvedAt}
        targetMinutes={incident.resolutionTargetMinutes}
        now={now}
      />
    </div>
  )
}
