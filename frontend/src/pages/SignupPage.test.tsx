import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, readToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/router'
import { mockFetch } from '@/test/fetchMock'

const created = {
  token: 'new-token',
  expiresAt: '2026-11-03T11:00:00Z',
  user: { id: 1, email: 'ana@acme.test', displayName: 'Ana', role: 'ADMIN' },
  org: { id: 5, name: 'Acme', slug: 'acme' },
}

function renderSignup() {
  const router = createMemoryRouter(routes, { initialEntries: ['/signup'] })
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
  return router
}

async function fillForm(email = 'ana@acme.test') {
  await userEvent.type(await screen.findByLabelText('Email'), email)
  await userEvent.type(screen.getByLabelText('Password'), 'DevPassw0rd!')
  await userEvent.type(screen.getByLabelText('Your name'), 'Ana')
  await userEvent.type(screen.getByLabelText('Org name'), 'Acme')
}

const signupCalls = (fetchMock: ReturnType<typeof mockFetch>) =>
  fetchMock.mock.calls.filter(([url, init]) => url === '/api/auth/signup' && init?.method === 'POST')

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('SignupPage', () => {
  // TC-FR-01.4g: sign-up creates the org and opens the app in it.
  it('creates the org and lands on Home in it', async () => {
    const fetchMock = mockFetch({ 'POST /api/auth/signup': { status: 201, body: created } })
    const router = renderSignup()

    await fillForm()
    await userEvent.click(screen.getByRole('button', { name: 'Create org' }))

    const breadcrumb = await screen.findByRole('navigation', { name: 'Breadcrumb' })
    expect(within(breadcrumb).getByText('Acme')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/home')
    expect(readToken()).toBe('new-token')
    const [[, init]] = signupCalls(fetchMock)
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ana@acme.test', password: 'DevPassw0rd!', displayName: 'Ana', orgName: 'Acme' })
  })

  it('shows a taken email next to the email field', async () => {
    mockFetch({ 'POST /api/auth/signup': { status: 409, body: { status: 409, message: 'An account with this email already exists' } } })
    renderSignup()

    await fillForm('taken@acme.test')
    await userEvent.click(screen.getByRole('button', { name: 'Create org' }))

    expect(await screen.findByText('An account with this email already exists')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription('An account with this email already exists')
  })

  it("puts the server's field errors next to their fields", async () => {
    mockFetch({
      'POST /api/auth/signup': {
        status: 400,
        body: { status: 400, message: 'Validation failed', errors: { orgName: 'Org name is at most 100 characters' } },
      },
    })
    renderSignup()

    await fillForm()
    await userEvent.click(screen.getByRole('button', { name: 'Create org' }))

    expect(await screen.findByText('Org name is at most 100 characters')).toBeInTheDocument()
    expect(screen.getByLabelText('Org name')).toHaveAccessibleDescription('Org name is at most 100 characters')
  })

  it('shows a message under each empty field and sends nothing', async () => {
    const fetchMock = mockFetch({})
    renderSignup()

    await userEvent.click(await screen.findByRole('button', { name: 'Create org' }))

    expect(await screen.findByText('Enter a valid email')).toBeInTheDocument()
    expect(screen.getByText('At least 8 characters')).toBeInTheDocument()
    expect(screen.getByText('Enter your name')).toBeInTheDocument()
    expect(screen.getByText('Enter a name for your org')).toBeInTheDocument()
    expect(signupCalls(fetchMock)).toHaveLength(0)
  })

  it('links back to sign-in', async () => {
    const router = renderSignup()

    await userEvent.click(await screen.findByRole('link', { name: 'Sign in' }))

    expect(router.state.location.pathname).toBe('/login')
  })
})
