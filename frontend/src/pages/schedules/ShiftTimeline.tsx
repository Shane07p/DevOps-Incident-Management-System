import { formatIst, formatIstClock, istDayStart } from '@/lib/time'
import { cn } from '@/lib/utils'
import { coverage } from './schedule'
import type { Shift } from '@/api/onCall'

const DAY_MS = 86_400_000
const HOURS = [0, 6, 12, 18, 24]

const percent = (value: number) => `${(value * 100).toFixed(3)}%`

/**
 * Today in IST from 00:00 to 24:00: each shift as a bar, each gap as the fallback contact's time, and a line at
 * the current time. The bars carry their names for screen readers; the figure's caption names the gaps.
 */
export function ShiftTimeline({ shifts, now, fallbackName }: { shifts: Shift[]; now: number; fallbackName: string | null }) {
  const dayStart = istDayStart(now)
  const dayEnd = dayStart + DAY_MS
  const parts = coverage(shifts, dayStart, dayEnd)
  const at = (time: number) => percent((time - dayStart) / DAY_MS)

  return (
    <figure aria-label="Today's coverage, in IST" className="flex flex-col gap-3 px-[19px] pb-5">
      <div className="relative h-4 font-mono text-[11px] text-muted-foreground" aria-hidden="true">
        {HOURS.map((hour) => (
          <span
            key={hour}
            className={cn('absolute', hour === 0 ? 'left-0' : hour === 24 ? 'right-0' : '-translate-x-1/2')}
            style={hour > 0 && hour < 24 ? { left: percent(hour / 24) } : undefined}
          >
            {String(hour).padStart(2, '0')}:00
          </span>
        ))}
      </div>
      <div className="relative h-10 rounded-md bg-muted">
        {parts.map((part) => {
          const current = part.start <= now && now < part.end
          const label =
            part.kind === 'shift'
              ? `${part.shift.responderName}, ${formatIst(part.shift.startTime)} to ${formatIst(part.shift.endTime)}`
              : `${formatIstClock(part.start)} to ${formatIstClock(part.end, dayEnd)}: ${fallbackName ? `${fallbackName}, the fallback` : 'nobody'}`
          return (
            <div
              key={part.start}
              title={label}
              className={cn(
                'absolute inset-y-1 overflow-hidden rounded-[5px] border px-2 text-[12px] leading-[30px] text-ellipsis whitespace-nowrap',
                part.kind === 'gap'
                  ? 'border-transparent bg-sev-2-bg text-sev-2-fg'
                  : current
                    ? 'border-primary bg-primary/15 font-medium text-foreground'
                    : 'bg-card font-medium text-foreground',
              )}
              style={{ left: at(part.start), width: at(dayStart + (part.end - part.start)) }}
            >
              <span className="sr-only">{label}</span>
              <span aria-hidden="true">
                {part.kind === 'shift' ? part.shift.responderName : fallbackName ? `${fallbackName} (fallback)` : 'Nobody'}
              </span>
            </div>
          )
        })}
        <div aria-hidden="true" className="absolute -inset-y-1 w-px bg-primary" style={{ left: at(now) }} />
      </div>
      <div className="relative h-4 font-mono text-[11px] text-primary" aria-hidden="true">
        <span className="absolute -translate-x-1/2 whitespace-nowrap" style={{ left: `clamp(32px, ${at(now)}, calc(100% - 32px))` }}>
          Now {formatIstClock(now)}
        </span>
      </div>
    </figure>
  )
}
