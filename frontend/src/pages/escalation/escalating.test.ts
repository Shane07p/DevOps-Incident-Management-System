import { describe, expect, it } from 'vitest'
import { escalationNow } from './escalating'

const levels = [
  { id: 1, stepNumber: 1, responderId: 2, responderDisplayName: 'Asha', waitMinutes: 5 },
  { id: 2, stepNumber: 2, responderId: 3, responderDisplayName: 'Rohan', waitMinutes: 10 },
]
const opened = '2026-10-28T06:00:00Z'
const minutesIn = (minutes: number) => Date.parse(opened) + minutes * 60_000

describe('escalationNow', () => {
  it('names the first level before anyone is paged', () => {
    expect(escalationNow(levels, 'Dev Admin', opened, minutesIn(2))).toBe('Nobody acknowledged yet. Asha (level 1) is paged in 3 min.')
  })

  it('names the level paged last and the next one, at the moment a level is paged', () => {
    expect(escalationNow(levels, 'Dev Admin', opened, minutesIn(5))).toBe('Asha (level 1) was paged just now. Rohan (level 2) is paged in 10 min.')
  })

  it('goes on to the fallback after the last level, the last wait later', () => {
    expect(escalationNow(levels, 'Dev Admin', opened, minutesIn(20))).toBe('Rohan (level 2) was paged 5 min ago. Dev Admin, the fallback, is paged in 5 min.')
    expect(escalationNow(levels, 'Dev Admin', opened, minutesIn(25))).toBe('Every level and Dev Admin, the fallback, have been paged.')
  })

  it('says when nobody comes after the last level', () => {
    expect(escalationNow(levels, null, opened, minutesIn(40))).toBe('Rohan (level 2) was paged 25 min ago. That is the last level, and there is no fallback contact.')
  })

  it('says nothing without levels', () => {
    expect(escalationNow([], 'Dev Admin', opened, minutesIn(40))).toBeNull()
  })
})
