import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { mockFetch, users } from '@/test/fetchMock'

function renderHome() {
  writeToken('test-token')
  mockFetch({ 'GET /api/auth/me': { body: { user: users[0], org: { id: 5, name: 'Acme', slug: 'acme' } } } })
  const router = createMemoryRouter(routes, { initialEntries: ['/home'] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
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
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('TopBar', () => {
  it("shows the org's name in the header and the account menu", async () => {
    renderHome()

    const breadcrumb = await screen.findByRole('navigation', { name: 'Breadcrumb' })
    expect(within(breadcrumb).getByText('Acme')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Account' }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByText('Acme')).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: 'Members' })).toBeInTheDocument()
  })
})
