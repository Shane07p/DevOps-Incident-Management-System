import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { isBreached, slaState } from '@/lib/sla'
import { SlaClock } from './SlaClock'

// Declared 08:30 UTC with a 60-minute response target, so the clock is due at 09:30:00.
const dueAt = '2026-10-20T09:30:00Z'
const at = (time: string) => Date.parse(`2026-10-20T${time}Z`)

function renderClock(now: number, stoppedAt: string | null = null) {
  render(<SlaClock label="Response" dueAt={dueAt} stoppedAt={stoppedAt} targetMinutes={60} now={now} />)
  return screen.getByRole('group', { name: 'Response clock' })
}

// TC-FR-16.3e: a controlled clock one second before, exactly at, and one second after the target.
describe('SlaClock while it runs', () => {
  it('one second before the target: 1 s left, not breached', () => {
    expect(renderClock(at('09:29:59'))).toHaveTextContent('1 s left')
    expect(isBreached(slaState(dueAt, null, at('09:29:59')))).toBe(false)
  })

  it('exactly at the target: 0 s left, still not breached', () => {
    const clock = renderClock(at('09:30:00'))
    expect(clock).toHaveTextContent('0 s left')
    expect(clock).not.toHaveTextContent('Breached')
    expect(isBreached(slaState(dueAt, null, at('09:30:00')))).toBe(false)
  })

  it('one second after the target: Breached, 1 s overdue', () => {
    expect(renderClock(at('09:30:01'))).toHaveTextContent('Breached · 1 s overdue')
    expect(isBreached(slaState(dueAt, null, at('09:30:01')))).toBe(true)
  })

  it('counts minutes once more than a minute is left', () => {
    expect(renderClock(at('08:50:00'))).toHaveTextContent('40 min left')
  })
})

describe('SlaClock once it stops', () => {
  // The time on screen no longer matters once the clock has stopped.
  const later = at('23:00:00')

  it('stopped one second before the target: Met', () => {
    expect(renderClock(later, '2026-10-20T09:29:59Z')).toHaveTextContent('Met')
  })

  it('stopped exactly at the target: Met, since reaching it is not a breach', () => {
    const clock = renderClock(later, '2026-10-20T09:30:00Z')
    expect(clock).toHaveTextContent('Met')
    expect(clock).not.toHaveTextContent('Breached')
  })

  it('stopped one second after the target: Breached, 1 s late', () => {
    const clock = renderClock(later, '2026-10-20T09:30:01Z')
    expect(clock).toHaveTextContent('Breached')
    expect(clock).toHaveTextContent('Stopped 20 Oct, 15:00 IST, 1 s late')
  })
})
