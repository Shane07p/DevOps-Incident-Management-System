import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, readToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { devOrg, mockFetch, users } from '@/test/fetchMock'
import { LoginPage } from '../LoginPage'
import { SignupPage } from '../SignupPage'
import { OAuthCallbackPage } from './OAuthCallbackPage'
import { OAuthSignupPage } from './OAuthSignupPage'

// Sign in with Google and GitHub (D50).

const session = (token: string) => ({ token, expiresAt: '2026-11-18T12:00:00Z', user: users[0], org: devOrg })

function renderAt(path: string, hash = '') {
  // The pages read the code from the real address bar's fragment, as a browser would show it.
  window.history.replaceState(null, '', path + hash)
  return render(
    <StrictMode>
      <ThemeProvider>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <AuthProvider>
            <MemoryRouter initialEntries={[path]}>
              <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/signup" element={<SignupPage />} />
                <Route path="/auth/callback" element={<OAuthCallbackPage />} />
                <Route path="/signup/finish" element={<OAuthSignupPage />} />
                <Route path="/home" element={<p>Home page</p>} />
              </Routes>
            </MemoryRouter>
          </AuthProvider>
        </QueryClientProvider>
      </ThemeProvider>
    </StrictMode>,
  )
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

describe('Google and GitHub buttons', () => {
  it('shows a button for each provider the server has set up, linking to its sign-in', async () => {
    mockFetch({ 'GET /api/auth/oauth/providers': { body: { providers: ['google', 'github'] } } })
    renderAt('/login')

    const google = await screen.findByRole('link', { name: 'Continue with Google' })
    expect(google).toHaveAttribute('href', '/api/auth/oauth2/authorization/google')
    expect(screen.getByRole('link', { name: 'Continue with GitHub' })).toHaveAttribute('href', '/api/auth/oauth2/authorization/github')
    expect(screen.getByText('or with your email')).toBeInTheDocument()
  })

  it('shows no buttons when the server has none set up, and on sign-up too', async () => {
    const fetchMock = mockFetch({ 'GET /api/auth/oauth/providers': { body: { providers: [] } } })
    renderAt('/signup')

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/auth/oauth/providers', expect.anything()))
    expect(screen.queryByRole('link', { name: /Continue with/ })).not.toBeInTheDocument()
    expect(screen.queryByText('or with your email')).not.toBeInTheDocument()
  })

  it('says why a provider sign-in came back to the sign-in page', async () => {
    mockFetch({ 'GET /api/auth/oauth/providers': { body: { providers: ['google'] } } })
    renderAt('/login?oauth=unverified')

    expect(await screen.findByRole('alert')).toHaveTextContent('That Google or GitHub account has no verified email')
  })
})

describe('OAuthCallbackPage', () => {
  it('swaps the code once for a session, clears it from the address bar, and opens Home', async () => {
    const fetchMock = mockFetch({ 'POST /api/auth/oauth/exchange': { body: session('oauth-token') } })
    renderAt('/auth/callback', '#code=one-time-code')

    expect(await screen.findByText('Home page')).toBeInTheDocument()
    const exchanges = fetchMock.mock.calls.filter(([url]) => url === '/api/auth/oauth/exchange')
    // StrictMode runs effects twice; a code works once, so it must be sent once.
    expect(exchanges).toHaveLength(1)
    expect(JSON.parse(String(exchanges[0][1]?.body))).toEqual({ code: 'one-time-code' })
    expect(window.location.hash).toBe('')
    expect(readToken()).toBe('oauth-token')
  })

  it('shows the server message for a used or expired code, with a way back', async () => {
    mockFetch({
      'POST /api/auth/oauth/exchange': {
        status: 401,
        body: { status: 401, message: 'This sign-in link has expired or was already used. Sign in again.' },
      },
    })
    renderAt('/auth/callback', '#code=used')

    expect(await screen.findByRole('alert')).toHaveTextContent('This sign-in link has expired or was already used.')
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login')
  })

  it('sends nothing when the link has no code', async () => {
    const fetchMock = mockFetch({})
    renderAt('/auth/callback')

    expect(await screen.findByRole('alert')).toHaveTextContent('This sign-in link is incomplete.')
    expect(fetchMock).not.toHaveBeenCalledWith('/api/auth/oauth/exchange', expect.anything())
  })
})

describe('OAuthSignupPage', () => {
  it('shows who the provider confirmed, starts with their name, and creates the workspace', async () => {
    const fetchMock = mockFetch({
      'POST /api/auth/oauth/signup/details': { body: { email: 'nina@example.test', displayName: 'Nina Person', provider: 'github' } },
      'POST /api/auth/oauth/signup': { status: 201, body: session('new-token') },
    })
    renderAt('/signup/finish', '#code=signup-code')

    expect(await screen.findByText(/GitHub confirmed nina@example.test/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Your name')).toHaveValue('Nina Person'))
    expect(screen.getByLabelText('Organisation')).toHaveAttribute('id', 'orgName')
    await userEvent.type(screen.getByLabelText('Organisation'), 'Acme Payments')
    await userEvent.click(screen.getByRole('button', { name: 'Create a workspace' }))

    expect(await screen.findByText('Home page', {}, { timeout: 3000 })).toBeInTheDocument()
    const signup = fetchMock.mock.calls.find(([url]) => url === '/api/auth/oauth/signup')
    expect(JSON.parse(String(signup?.[1]?.body))).toEqual({ code: 'signup-code', orgName: 'Acme Payments', displayName: 'Nina Person' })
  })

  it('says the link expired when its code no longer works', async () => {
    mockFetch({
      'POST /api/auth/oauth/signup/details': {
        status: 401,
        body: { status: 401, message: 'This sign-in link has expired or was already used. Sign in again.' },
      },
    })
    renderAt('/signup/finish', '#code=old')

    expect(await screen.findByRole('heading', { name: 'This sign-up link has expired' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to sign up' })).toHaveAttribute('href', '/signup')
  })

  it('says the link is incomplete when it has no code, and asks the server nothing', async () => {
    const fetchMock = mockFetch({})
    renderAt('/signup/finish')

    expect(await screen.findByRole('alert')).toHaveTextContent('The link is incomplete. Start again from the sign-up page.')
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/auth/oauth'))).toBe(false)
  })

  it('says Settle could not be reached when the details do not load', async () => {
    mockFetch({ 'POST /api/auth/oauth/signup/details': { status: 503, body: { status: 503, message: 'Service unavailable' } } })
    renderAt('/signup/finish', '#code=signup-code')

    expect(await screen.findByRole('alert')).toHaveTextContent('Settle could not be reached. Try again in a moment.')
  })

  it("names an unknown provider plainly, and shows each kind of failure where it belongs", async () => {
    let attempts = 0
    mockFetch({
      'POST /api/auth/oauth/signup/details': { body: { email: 'nina@example.test', displayName: 'Nina Person', provider: 'gitlab' } },
      'POST /api/auth/oauth/signup': () => {
        attempts += 1
        if (attempts === 1) return { status: 409, body: { status: 409, message: 'An account with this email already exists' } }
        if (attempts === 2) {
          return { status: 400, body: { status: 400, message: 'Validation failed', errors: { orgName: 'Use at most 100 characters' } } }
        }
        return { status: 500, body: { status: 500, message: 'Internal error' } }
      },
    })
    renderAt('/signup/finish', '#code=signup-code')

    expect(await screen.findByText(/Your provider confirmed nina@example.test/)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Organisation'), 'Acme Payments')
    const submit = screen.getByRole('button', { name: 'Create a workspace' })

    await userEvent.click(submit)
    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists')

    await userEvent.click(screen.getByRole('button', { name: 'Create a workspace' }))
    expect(await screen.findByText('Use at most 100 characters')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Create a workspace' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Settle could not be reached. Try again in a moment.')
    expect(screen.queryByText('Home page')).not.toBeInTheDocument()
  })
})
