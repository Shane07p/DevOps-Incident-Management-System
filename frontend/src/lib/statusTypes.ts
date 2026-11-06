export type DayState = 'ok' | 'degraded' | 'down'

/** Date for a 90-day uptime bar (0 = 89 days ago). */
export function dayLabel(today: Date, index: number) {
  const day = new Date(today.getTime() - (89 - index) * 86_400_000)
  return day.toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    timeZone: 'Asia/Kolkata',
  })
}
