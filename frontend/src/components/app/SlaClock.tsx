import { CircleCheck, Siren, Timer } from 'lucide-react'
import { formatDuration, slaHeadline, slaState } from '@/lib/sla'
import { formatIst, formatMinutes } from '@/lib/time'
import { cn } from '@/lib/utils'

const LOOK = {
  running: { icon: Timer, text: 'text-foreground', dot: 'bg-sla-running' },
  overdue: { icon: Siren, text: 'text-sla-breached-fg', dot: 'bg-sla-breached' },
  met: { icon: CircleCheck, text: 'text-sla-met-fg', dot: 'bg-sla-met' },
  breached: { icon: Siren, text: 'text-sla-breached-fg', dot: 'bg-sla-breached' },
} as const

/** One clock on the incident page: time left or overdue while it runs, Met or Breached once it stops. */
export function SlaClock({
  label,
  dueAt,
  stoppedAt,
  targetMinutes,
  now,
}: {
  label: string
  dueAt: string
  stoppedAt: string | null
  targetMinutes: number
  now: number
}) {
  const state = slaState(dueAt, stoppedAt, now)
  const look = LOOK[state.kind]
  const Icon = look.icon
  return (
    <div role="group" aria-label={`${label} clock`} className="flex flex-col gap-1 rounded-lg border bg-card p-4">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <span aria-hidden="true" className={cn('size-2 rounded-full', look.dot)} />
        {label}
      </span>
      <span className={cn('flex items-center gap-1.5 font-mono text-lg font-semibold tnum', look.text)}>
        <Icon className="size-4 shrink-0" aria-hidden="true" />
        {slaHeadline(state)}
      </span>
      <span className="text-xs text-muted-foreground">
        {stoppedAt === null ? 'Due' : 'Was due'} <span className="font-mono tnum">{formatIst(dueAt)}</span>, {formatMinutes(targetMinutes)} from
        declaration
      </span>
      {stoppedAt !== null && (
        <span className="text-xs text-muted-foreground">
          Stopped <span className="font-mono tnum">{formatIst(stoppedAt)}</span>
          {state.kind === 'breached' && `, ${formatDuration(state.msOver)} late`}
        </span>
      )}
    </div>
  )
}

/** One clock of a listed incident, compact: "Response · 12 min left", or "Response · Met" once it stopped. */
export function SlaBadge({
  name,
  dueAt,
  now,
  stoppedAt = null,
  className,
}: {
  name: string
  dueAt: string
  now: number
  stoppedAt?: string | null
  className?: string
}) {
  const state = slaState(dueAt, stoppedAt, now)
  const look = LOOK[state.kind]
  return (
    <span className={cn('inline-flex items-center gap-1.5 font-mono text-xs whitespace-nowrap tnum', look.text, className)}>
      <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', look.dot)} />
      {name} · {slaHeadline(state)}
    </span>
  )
}
