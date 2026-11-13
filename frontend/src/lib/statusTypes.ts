/** One day on a 90-day uptime bar. `none` is a day with no checks. */
export type DayState = 'ok' | 'degraded' | 'down' | 'none'

/** The date shown for day `index` (0 = 89 days before `today`). */
export function dayLabel(today: Date, index: number) {
  const day = new Date(today.getTime() - (89 - index) * 86_400_000)
  return day.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', timeZone: 'Asia/Kolkata' })
}
