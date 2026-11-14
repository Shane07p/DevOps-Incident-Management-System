import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CriticalityBadge, SeverityBadge, StatusBadge } from './badges'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'

const filledBars = (container: HTMLElement) =>
  [...container.querySelectorAll('rect')].filter((bar) => bar.getAttribute('opacity') === '1').length

describe('SeverityBadge', () => {
  it('fills four bars for SEV1 and one for SEV4', () => {
    const { container, rerender } = render(<SeverityBadge severity="SEV1" />)
    expect(filledBars(container)).toBe(4)

    rerender(<SeverityBadge severity="SEV4" />)
    expect(filledBars(container)).toBe(1)
  })

  it('shows the severity as text, not only as bars', () => {
    render(<SeverityBadge severity="SEV2" />)
    expect(screen.getByText('SEV2')).toBeInTheDocument()
  })
})

describe('StatusBadge', () => {
  it('shows the status as a word, not only as an icon', () => {
    render(<StatusBadge status="INVESTIGATING" />)
    expect(screen.getByText('Investigating')).toBeInTheDocument()
  })

  it('colours only a finished incident', () => {
    const { rerender } = render(<StatusBadge status="RESOLVED" />)
    expect(screen.getByText('Resolved')).toHaveClass('text-status-resolved')

    rerender(<StatusBadge status="OPEN" />)
    expect(screen.getByText('Open')).toHaveClass('text-foreground')
  })
})

describe('CriticalityBadge', () => {
  it('styles HIGH and MEDIUM differently', () => {
    const { rerender } = render(<CriticalityBadge criticality="HIGH" />)
    const high = screen.getByText('high').className

    rerender(<CriticalityBadge criticality="MEDIUM" />)
    expect(screen.getByText('medium').className).not.toBe(high)
  })
})

describe('LoadingState and ErrorState', () => {
  it('announces loading to screen readers', () => {
    render(<LoadingState message="Loading incidents" />)
    expect(screen.getByRole('status')).toHaveTextContent('Loading incidents')
  })

  it('offers a retry that calls back', () => {
    const onRetry = vi.fn()
    render(<ErrorState message="Could not load incidents" onRetry={onRetry} />)

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Could not load incidents')
    expect(onRetry).toHaveBeenCalledOnce()
  })
})
