import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { NotBuiltYetPage } from './NotBuiltYetPage'

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <NotBuiltYetPage />
    </MemoryRouter>,
  )
}

describe('NotBuiltYetPage', () => {
  it('names a planned screen and the sprint it lands in', () => {
    renderAt('/metrics/edit')

    expect(screen.getByRole('heading', { name: 'Metrics' })).toBeInTheDocument()
    expect(screen.getByText('Traffic, errors, latency, and resources.')).toBeInTheDocument()
    expect(screen.getByText('This screen is planned for Sprint 4.')).toBeInTheDocument()
  })

  it('says a known screen with no planned sprint is not built yet', () => {
    renderAt('/history')

    expect(screen.getByRole('heading', { name: 'History' })).toBeInTheDocument()
    expect(screen.getByText('This screen is not built yet.')).toBeInTheDocument()
  })

  it('has a plain heading for a path that is no screen at all', () => {
    renderAt('/nowhere')

    expect(screen.getByRole('heading', { name: 'Not built yet' })).toBeInTheDocument()
    expect(screen.getByText('This screen is not built yet.')).toBeInTheDocument()
  })
})
