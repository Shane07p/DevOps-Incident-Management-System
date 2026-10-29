import type { Shift } from '@/api/onCall'
import { formatIst, formatIstDay, istDayStart } from '@/lib/time'

const DAYS = 7
const DAY_MS = 86_400_000

const percent = (value: number) => `${(value * 100).toFixed(3)}%`

/** Seven IST days from today, with each shift as a bar and a line at the current time. */
export function ShiftTimeline({ shifts, now }: { shifts: Shift[]; now: number }) {
  const windowStart = istDayStart(now)
  const windowEnd = windowStart + DAYS * DAY_MS
  const span = windowEnd - windowStart
  const visible = shifts.filter((shift) => Date.parse(shift.startTime) < windowEnd && Date.parse(shift.endTime) > windowStart)

  return (
    <figure aria-label="Shifts over the next 7 days, in IST" className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <div className="grid grid-cols-7 text-xs font-medium text-muted-foreground">
        {Array.from({ length: DAYS }, (_, day) => (
          <span key={day} className="border-l pl-1.5 first:border-l-0 first:pl-0">
            {formatIstDay(windowStart + day * DAY_MS)}
          </span>
        ))}
      </div>
      <div className="relative h-10 rounded-md bg-muted">
        {visible.map((shift) => {
          const start = Math.max(Date.parse(shift.startTime), windowStart)
          const end = Math.min(Date.parse(shift.endTime), windowEnd)
          const label = `${shift.responderUsername}, ${formatIst(shift.startTime)} to ${formatIst(shift.endTime)}`
          return (
            <div
              key={shift.id}
              title={label}
              className="absolute inset-y-1 overflow-hidden rounded-sm bg-primary px-1.5 text-xs leading-8 font-semibold text-ellipsis whitespace-nowrap text-primary-foreground"
              style={{ left: percent((start - windowStart) / span), width: percent((end - start) / span) }}
            >
              <span className="sr-only">{label}</span>
              <span aria-hidden="true">{shift.responderUsername}</span>
            </div>
          )
        })}
        <div
          aria-hidden="true"
          className="absolute inset-y-0 w-0.5 bg-status-open"
          style={{ left: percent((now - windowStart) / span) }}
        />
      </div>
      {visible.length === 0 && <figcaption className="text-xs text-muted-foreground">No shifts in the next 7 days.</figcaption>}
    </figure>
  )
}
