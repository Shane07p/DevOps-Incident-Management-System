// The server stores and sends UTC; the app shows everything in IST, like the top-bar clock.
const istFormat = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** "20 Oct, 14:05 IST" from an ISO-8601 UTC timestamp. */
export function formatIst(timestamp: string) {
  return `${istFormat.format(new Date(timestamp))} IST`
}

const IST_OFFSET_MS = 330 * 60_000
const DAY_MS = 86_400_000

/** A `datetime-local` value typed in IST ("2026-10-28T09:00") as a UTC timestamp ("2026-10-28T03:30:00.000Z"). */
export function istInputToUtc(value: string) {
  return new Date(`${value}:00+05:30`).toISOString()
}

/** Midnight IST of the day that contains `at`, in epoch milliseconds. */
export function istDayStart(at: number) {
  const ist = at + IST_OFFSET_MS
  return ist - (ist % DAY_MS) - IST_OFFSET_MS
}

const istDayFormat = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', weekday: 'short', day: 'numeric' })

/** "Wed 28" for the IST day that contains `at`. */
export function formatIstDay(at: number) {
  return istDayFormat.format(new Date(at))
}

/** 15 → "15 min", 60 → "1 h", 90 → "1 h 30 min", 1440 → "1 d". */
export function formatMinutes(total: number) {
  const days = Math.floor(total / 1440)
  const hours = Math.floor((total % 1440) / 60)
  const minutes = total % 60
  const parts = [days && `${days} d`, hours && `${hours} h`, minutes && `${minutes} min`].filter(Boolean)
  return parts.length > 0 ? parts.join(' ') : '0 min'
}
