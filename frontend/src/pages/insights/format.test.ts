import { describe, expect, it } from 'vitest'
import { formatDuration, formatPercentage } from './format'

describe('formatDuration', () => {
  it('formats minutes into rounded hours and minutes', () => {
    expect(formatDuration(14.5)).toBe('15 min')
    expect(formatDuration(60)).toBe('1 h')
    expect(formatDuration(135)).toBe('2 h 15 min')
    expect(formatDuration(260)).toBe('4 h 20 min')
    expect(formatDuration(0)).toBe('0 min')
  })

  it('displays "No data yet" for null and undefined', () => {
    expect(formatDuration(null)).toBe('No data yet')
    expect(formatDuration(undefined)).toBe('No data yet')
  })
})

describe('formatPercentage', () => {
  it('formats proportion into percentage with 1 decimal place', () => {
    expect(formatPercentage(0.048)).toBe('4.8%')
    expect(formatPercentage(0.85)).toBe('85.0%')
    expect(formatPercentage(0)).toBe('0.0%')
  })

  it('displays "No data yet" for null and undefined', () => {
    expect(formatPercentage(null)).toBe('No data yet')
    expect(formatPercentage(undefined)).toBe('No data yet')
  })
})
