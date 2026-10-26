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

/** 15 → "15 min", 60 → "1 h", 90 → "1 h 30 min", 1440 → "1 d". */
export function formatMinutes(total: number) {
  const days = Math.floor(total / 1440)
  const hours = Math.floor((total % 1440) / 60)
  const minutes = total % 60
  const parts = [days && `${days} d`, hours && `${hours} h`, minutes && `${minutes} min`].filter(Boolean)
  return parts.length > 0 ? parts.join(' ') : '0 min'
}
