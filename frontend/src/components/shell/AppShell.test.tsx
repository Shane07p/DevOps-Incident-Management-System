import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { devOrg, mockFetch, users } from '@/test/fetchMock'

const viewer = { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER', enabled: true }
const [admin, engineer] = users

function renderShell(user: { role: string } = engineer, path = '/home') {
  writeToken('test-token')
  mockFetch({ 'GET /api/auth/me': { body: { user, org: devOrg } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <TooltipProvider>
            <RouterProvider router={router} />
          </TooltipProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
  return { router, ...view }
}

const sidebar = () => screen.findByRole('navigation', { name: 'Main' })

afterEach(() => {
  clearToken()
  localStorage.removeItem('settle-theme')
  document.documentElement.classList.remove('dark')
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Theme', () => {
  it('switches from the top bar and is remembered after a reload', async () => {
    const { unmount } = renderShell()
    await sidebar()

    await userEvent.click(screen.getByRole('button', { name: 'Switch to dark theme' }))

    expect(document.documentElement).toHaveClass('dark')
    expect(localStorage.getItem('settle-theme')).toBe('dark')

    unmount()
    document.documentElement.classList.remove('dark')
    renderShell()
    await sidebar()

    expect(document.documentElement).toHaveClass('dark')
    expect(screen.getByRole('button', { name: 'Switch to light theme' })).toBeInTheDocument()
  })

  it('follows the system preference until someone picks a theme', async () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query: string) =>
        ({
          matches: query === '(prefers-color-scheme: dark)',
          media: query,
          addEventListener: () => {},
          removeEventListener: () => {},
        }) as unknown as MediaQueryList,
    )
    renderShell()
    await sidebar()

    expect(document.documentElement).toHaveClass('dark')
    expect(localStorage.getItem('settle-theme')).toBeNull()
  })
})

describe('Keyboard shortcuts', () => {
  it('opens Incidents with G then I, and Services with G then S', async () => {
    const { router } = renderShell()
    await sidebar()

    await userEvent.keyboard('gi')
    expect(router.state.location.pathname).toBe('/incidents')

    await userEvent.keyboard('gs')
    expect(router.state.location.pathname).toBe('/services')
  })

  it('opens the declare dialog with C for an Engineer, and does nothing for a Viewer', async () => {
    const { unmount } = renderShell(engineer)
    await sidebar()
    await userEvent.keyboard('c')
    expect(await screen.findByRole('dialog', { name: 'Declare an incident' })).toBeInTheDocument()
    unmount()

    renderShell(viewer)
    await sidebar()
    await userEvent.keyboard('c')
    expect(screen.queryByRole('dialog', { name: 'Declare an incident' })).not.toBeInTheDocument()
  })

  it('lists the shortcuts on ?, without declaring for a Viewer', async () => {
    renderShell(viewer)
    await sidebar()

    await userEvent.keyboard('?')

    const dialog = await screen.findByRole('dialog', { name: 'Keyboard shortcuts' })
    expect(within(dialog).getByText('Incidents')).toBeInTheDocument()
    expect(within(dialog).queryByText('Declare an incident')).not.toBeInTheDocument()
  })

  it('ignores single keys while someone types in a field', async () => {
    const { router } = renderShell()
    await sidebar()

    await userEvent.keyboard('{Control>}k{/Control}')
    const search = await screen.findByRole('dialog', { name: 'Go to' })
    await userEvent.type(within(search).getByRole('combobox'), 'gi')

    expect(router.state.location.pathname).toBe('/home')
  })

  it('opens the command menu with Ctrl+K and goes to the chosen page', async () => {
    const { router } = renderShell()
    await sidebar()

    await userEvent.keyboard('{Control>}k{/Control}')
    const search = await screen.findByRole('dialog', { name: 'Go to' })
    await userEvent.click(within(search).getByRole('option', { name: 'Schedules' }))

    expect(router.state.location.pathname).toBe('/schedules')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Go to' })).not.toBeInTheDocument())
  })
})

describe('Navigation drawer', () => {
  it('opens and closes from the keyboard, and gives focus back to its button', async () => {
    renderShell()
    await sidebar()
    const menuButton = screen.getByRole('button', { name: 'Open navigation' })

    menuButton.focus()
    await userEvent.keyboard('{Enter}')

    const drawer = await screen.findByRole('dialog', { name: 'Navigation' })
    expect(within(drawer).getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(drawer).toContainElement(document.activeElement as HTMLElement)

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Navigation' })).not.toBeInTheDocument())
    expect(menuButton).toHaveFocus()
  })

  it('closes once a page is chosen', async () => {
    const { router } = renderShell()
    await sidebar()

    await userEvent.click(screen.getByRole('button', { name: 'Open navigation' }))
    const drawer = await screen.findByRole('dialog', { name: 'Navigation' })
    await userEvent.click(within(drawer).getByRole('link', { name: 'Runbooks' }))

    expect(router.state.location.pathname).toBe('/runbooks')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Navigation' })).not.toBeInTheDocument())
  })
})

describe('Sidebar', () => {
  it('marks the current page', async () => {
    renderShell(engineer, '/services')

    const nav = await sidebar()
    expect(within(nav).getByRole('link', { name: 'Services' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Incidents' })).not.toHaveAttribute('aria-current')
  })

  it('hides the Admin pages from an Engineer, in the sidebar and the command menu', async () => {
    renderShell(engineer)
    const nav = await sidebar()

    expect(within(nav).queryByRole('button', { name: 'Admin' })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Audit log' })).not.toBeInTheDocument()

    await userEvent.keyboard('{Control>}k{/Control}')
    const search = await screen.findByRole('dialog', { name: 'Go to' })
    expect(within(search).queryByRole('option', { name: 'Audit log' })).not.toBeInTheDocument()
    expect(within(search).getByRole('option', { name: 'Incidents' })).toBeInTheDocument()
  })

  it('shows an Admin the Admin pages behind a group that opens', async () => {
    renderShell(admin)
    const nav = await sidebar()

    const group = within(nav).getByRole('button', { name: 'Admin' })
    expect(group).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(group)

    expect(group).toHaveAttribute('aria-expanded', 'true')
    expect(within(nav).getByRole('link', { name: 'Audit log' })).toHaveAttribute('href', '/audit')
  })
})
