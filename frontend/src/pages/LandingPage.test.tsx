import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LandingPage } from './LandingPage'

function renderLanding() {
  return render(
    <MemoryRouter>
      <LandingPage />
    </MemoryRouter>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('Landing page', () => {
  it('leads with the product and sends both actions to sign-up and sign-in', () => {
    renderLanding()

    expect(screen.getByRole('heading', { level: 1, name: /Incident management for on.call teams/ })).toBeInTheDocument()
    const header = screen.getByRole('banner')
    expect(within(header).getByRole('link', { name: 'Create a workspace' })).toHaveAttribute('href', '/signup')
    expect(within(header).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
    // The hero, the closing call to action, and the footer all lead to sign-up.
    for (const link of screen.getAllByRole('link', { name: /Create a workspace/ })) {
      expect(link).toHaveAttribute('href', '/signup')
    }
  })

  it('has a section for every link in the header and the footer', () => {
    const { container } = renderLanding()

    const anchors = [...container.querySelectorAll('a[href^="#"]')].map((link) => link.getAttribute('href')!)
    expect(anchors.length).toBeGreaterThan(10)
    for (const anchor of new Set(anchors)) {
      expect(container.querySelector(anchor), `${anchor} has no section`).not.toBeNull()
    }
  })

  it('calls no API, so a visitor who is not signed in loads nothing from the backend', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    renderLanding()

    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('opens and closes the section menu from the keyboard on a narrow screen', async () => {
    renderLanding()
    const toggle = screen.getByRole('button', { name: 'Menu' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    toggle.focus()
    await userEvent.keyboard('{Enter}')

    expect(screen.getByRole('button', { name: 'Close menu' })).toHaveAttribute('aria-expanded', 'true')
    const menu = document.getElementById('landing-menu')!
    expect(within(menu).getByRole('link', { name: 'Questions' })).toHaveAttribute('href', '#questions')

    await userEvent.click(within(menu).getByRole('link', { name: 'Questions' }))
    expect(document.getElementById('landing-menu')).toBeNull()
  })

  it('answers the questions teams ask, including that the AI never changes an incident', () => {
    renderLanding()

    const questions = screen.getByRole('region', { name: 'Questions on‑call teams ask' })
    expect(within(questions).getByText('Does the AI change incidents?')).toBeInTheDocument()
    expect(within(questions).getByText(/No\. It suggests a cause and shows its evidence/)).toBeInTheDocument()
  })
})
