import { useState } from 'react'
import { dayLabel, type DayState } from '@/lib/statusTypes'
import { cn } from '@/lib/utils'

const DAY_COLOR: Record<DayState, string> = {
  ok: 'bg-health-operational',
  degraded: 'bg-health-degraded',
  down: 'bg-health-down',
}

const DAY_TEXT: Record<DayState, string> = { ok: 'no incidents', degraded: 'degraded', down: 'outage' }

/** 90 daily bars, oldest on the left. Hovering a bar names the day and what happened. */
export function UptimeBars({
  name,
  events,
  uptime,
  today,
  className,
}: {
  name: string
  events: Record<number, Exclude<DayState, 'ok'>>
  uptime?: string
  today?: Date
  className?: string
}) {
  const [hovered, setHovered] = useState<number | null>(null)
  const [mountedOn] = useState(() => new Date())
  const lastDay = today ?? mountedOn
  const incidents = Object.keys(events).length
  const days = Array.from({ length: 90 }, (_, index) => {
    const state: DayState = events[index] ?? 'ok'
    return { index, state, text: `${dayLabel(lastDay, index)}: ${DAY_TEXT[state]}` }
  })

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div
        role="img"
        aria-label={`${name}: ${incidents === 0 ? 'no incidents' : `${incidents} days with incidents`} in the last 90 days${uptime ? `, ${uptime} uptime` : ''}`}
        className="flex h-7 gap-0.5"
        onMouseLeave={() => setHovered(null)}
      >
        {days.map((day) => (
          <span
            key={day.index}
            title={day.text}
            onMouseEnter={() => setHovered(day.index)}
            className={cn(
              'min-w-0 flex-1 rounded-[2px] transition-transform',
              DAY_COLOR[day.state],
              hovered === day.index && 'scale-y-110',
            )}
          />
        ))}
      </div>
      <div className="flex justify-between font-mono text-[11px] font-medium text-muted-foreground">
        <span>90 days ago</span>
        <span>{hovered !== null ? days[hovered].text : uptime ? `${uptime} uptime` : ''}</span>
        <span>Today</span>
      </div>
    </div>
  )
}
