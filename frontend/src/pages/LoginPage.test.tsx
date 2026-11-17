import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { ThemeProvider } from '@/components/theme/ThemeProvider'
import { mockFetch } from '@/test/fetchMock'
import { LoginPage } from './LoginPage'

function renderLogin() {
  return render(
    <ThemeProvider>
      <QueryClientProvider client={new QueryClient()}>
        <AuthProvider>
          <MemoryRouter initialEntries={['/login']}>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/home" element={<p>Home page</p>} />
              <Route path="/signup" element={<p>Sign-up page</p>} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  )
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('LoginPage', () => {
  it('uses the field names as input ids, so labels and tests can find them', () => {
    renderLogin()
    expect(screen.getByLabelText('Work email')).toHaveAttribute('id', 'email')
    expect(screen.getByLabelText('Password')).toHaveAttribute('id', 'password')
  })

  it('shows a message under each empty field and sends nothing', async () => {
    const fetchMock = mockFetch({})
    renderLogin()

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Write your work email, like name@company.com.')).toBeInTheDocument()
    expect(screen.getByText('Write your password.')).toBeInTheDocument()
    expect(screen.getByLabelText('Work email')).toHaveAccessibleDescription('Write your work email, like name@company.com.')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('says the email or password is wrong without saying which', async () => {
    mockFetch({ 'POST /api/auth/login': { status: 401, body: { status: 401, message: 'Invalid email or password' } } })
    renderLogin()

    await userEvent.type(screen.getByLabelText('Work email'), 'engineer@settle.local')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
  })

  it('posts the email and password, then goes to Home', async () => {
    const fetchMock = mockFetch({
      'POST /api/auth/login': {
        body: {
          token: 't',
          expiresAt: '2026-10-14T08:00:00Z',
          user: { id: 2, email: 'engineer@settle.local', displayName: 'Dev Engineer', role: 'ENGINEER' },
          org: { id: 1, name: 'Dev org', slug: 'dev-org' },
        },
      },
    })
    renderLogin()

    await userEvent.type(screen.getByLabelText('Work email'), 'engineer@settle.local')
    await userEvent.type(screen.getByLabelText('Password'), 'DevPassw0rd!')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    // The wall turns to face you first, then the app opens.
    await waitFor(() => expect(document.querySelector('.product-wall')).toHaveAttribute('data-entering', 'true'))
    expect(await screen.findByText('Home page', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ email: 'engineer@settle.local', password: 'DevPassw0rd!' })
  })

  it('links to sign-up', async () => {
    renderLogin()

    await userEvent.click(screen.getByRole('link', { name: 'Create a workspace' }))

    expect(await screen.findByText('Sign-up page')).toBeInTheDocument()
  })

  it('waits until a field is left before saying it is wrong, and clears the message once it is being fixed', async () => {
    renderLogin()
    const email = screen.getByLabelText('Work email')

    await userEvent.type(email, 'asha')
    expect(screen.queryByText('Write your work email, like name@company.com.')).toBeNull()

    await userEvent.tab()
    expect(await screen.findByText('Write your work email, like name@company.com.')).toBeInTheDocument()
    expect(email).toHaveAttribute('aria-invalid', 'true')

    await userEvent.click(email)
    expect(screen.queryByText('Write your work email, like name@company.com.')).toBeNull()
    expect(email).not.toHaveAttribute('aria-invalid')
  })

  it('puts the company and the person from the work email on the wall of screens', async () => {
    renderLogin()

    await userEvent.type(screen.getByLabelText('Work email'), 'asha.patel@acme.example')

    const wall = document.querySelector('.product-wall')!
    expect(wall).toHaveTextContent('Acme')
    expect(wall).toHaveTextContent('Asha')
  })
})
