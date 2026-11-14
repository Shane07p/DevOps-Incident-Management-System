import { act, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LandingPage } from './LandingPage'
import { LANDING_STYLES } from './landing/kit'

afterEach(() => {
  vi.restoreAllMocks()
  // Each test is a fresh page load: the kit has not started yet.
  delete window.__settleLandingKit
  document.querySelectorAll('script[data-landing]').forEach((script) => script.remove())
})

// jsdom loads no stylesheets; this is the moment the kit's styles arrive in a browser.
async function stylesArrive() {
  await act(async () => {
    for (const link of document.head.querySelectorAll('link[data-landing]')) link.dispatchEvent(new Event('load'))
  })
}

const landingSheets = () => [...document.head.querySelectorAll<HTMLLinkElement>('link[data-landing]')].map((link) => link.getAttribute('href'))

describe('Landing page', () => {
  it('stays hidden until its styles arrive, so the unstyled page never shows', async () => {
    render(<LandingPage />)
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull()

    await stylesArrive()

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('leads with the product, and its actions go to sign-up and sign-in', async () => {
    render(<LandingPage />)
    await stylesArrive()

    expect(screen.getByRole('heading', { level: 1, name: 'Incident management for on-call teams' })).toBeInTheDocument()
    const header = screen.getByRole('banner')
    expect(within(header).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
    const createLinks = screen.getAllByRole('link', { name: /Create a workspace/ })
    expect(createLinks.length).toBeGreaterThanOrEqual(3)
    for (const link of createLinks) expect(link).toHaveAttribute('href', '/signup')
  })

  it('has a section for every link to a part of the page', () => {
    const { container } = render(<LandingPage />)

    const anchors = [...container.querySelectorAll('a[href^="#"]')].map((link) => link.getAttribute('href')!)
    expect(anchors.length).toBeGreaterThan(10)
    for (const anchor of new Set(anchors)) {
      expect(container.querySelector(anchor), `${anchor} has no section`).not.toBeNull()
    }
  })

  it('adds the kit and its page settings on arrival, and takes them away on leaving', () => {
    const { unmount } = render(<LandingPage />)

    expect(landingSheets()).toEqual(LANDING_STYLES.map((href) => `/landing/${href}`))
    expect(document.documentElement).toHaveAttribute('data-type', 'software')
    expect(document.head.querySelector('meta[name="site-kind"]')).toHaveAttribute('content', 'software')

    unmount()

    expect(landingSheets()).toEqual([])
    expect(document.documentElement).not.toHaveAttribute('data-type')
    expect(document.head.querySelector('meta[name="site-kind"]')).toBeNull()
  })

  it('calls no API, so a visitor who is not signed in loads nothing from the backend', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    render(<LandingPage />)

    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('answers the questions teams ask, including that the AI never changes an incident', async () => {
    render(<LandingPage />)
    await stylesArrive()

    expect(screen.getByRole('heading', { name: 'Does the AI change incidents?' })).toBeInTheDocument()
    expect(screen.getByText(/No\. It suggests a cause and shows its evidence/)).toBeInTheDocument()
  })
})
