import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { UptimeBars } from './UptimeBars'

const bars = () => screen.getByRole('img').querySelectorAll('span')

describe('UptimeBars', () => {
  it('fills 90 days from a sample, counting only the days with incidents', () => {
    render(<UptimeBars name="Checkout" events={{ 3: 'down', 40: 'degraded' }} today={new Date('2026-11-19T12:00:00Z')} />)

    expect(screen.getByRole('img')).toHaveAccessibleName('Checkout: 2 days with incidents in the last 90 days')
    expect(bars()).toHaveLength(90)
  })

  it('says what a hovered day held, and goes back to the uptime when the pointer leaves', () => {
    render(
      <UptimeBars
        name="Payments"
        uptime="99.95%"
        days={[
          { label: '18 Nov', state: 'ok', detail: '100% uptime' },
          { label: '19 Nov', state: 'down', detail: '97.2% uptime' },
        ]}
      />,
    )
    expect(screen.getByRole('img')).toHaveAccessibleName('Payments: 1 days with incidents in the last 90 days, 99.95% uptime')
    expect(screen.getByText('99.95% uptime')).toBeInTheDocument()

    fireEvent.mouseEnter(bars()[1])
    expect(screen.getByText('19 Nov: 97.2% uptime')).toBeInTheDocument()

    fireEvent.mouseLeave(screen.getByRole('img'))
    expect(screen.getByText('99.95% uptime')).toBeInTheDocument()
  })

  it('shows nothing in the middle when there is no uptime to name', () => {
    render(<UptimeBars name="Search" days={[{ label: '19 Nov', state: 'none', detail: 'no checks' }]} />)

    expect(screen.getByRole('img')).toHaveAccessibleName('Search: no incidents in the last 90 days')
    expect(screen.queryByText(/uptime/)).not.toBeInTheDocument()
  })
})
