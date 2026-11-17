/**
 * Rounds duration in minutes to the nearest integer and formats into hours and minutes.
 * Returns "No data yet" when value is null or undefined.
 */
export function formatDuration(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) {
    return 'No data yet'
  }
  const rounded = Math.round(minutes)
  const hours = Math.floor(rounded / 60)
  const mins = rounded % 60
  if (hours > 0 && mins > 0) return `${hours} h ${mins} min`
  if (hours > 0) return `${hours} h`
  return `${mins} min`
}

/**
 * Formats a 0.0 - 1.0 rate into a percentage with one decimal place.
 * Returns "No data yet" when rate is null or undefined.
 */
export function formatPercentage(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) {
    return 'No data yet'
  }
  return `${(rate * 100).toFixed(1)}%`
}
