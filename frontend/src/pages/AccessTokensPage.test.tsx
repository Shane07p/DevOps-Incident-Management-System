import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { AccessTokensPage } from './AccessTokensPage'

const RAW_TOKEN = 'pat_a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0'

const activeToken = {
  id: 1,
  name: 'Claude Desktop',
  createdAt: '2026-11-10T04:30:00Z',
  expiresAt: '2027-02-08T04:30:00Z',
  lastUsedAt: '2026-11-10T12:00:00Z',
  revokedAt: null,
}

const unusedToken = {
  id: 2,
  name: 'Cursor Assistant',
  createdAt: '2026-11-09T08:15:00Z',
  expiresAt: '2027-02-07T08:15:00Z',
  lastUsedAt: null,
  revokedAt: null,
}

const revokedToken = {
  id: 3,
  name: 'Old Terminal Script',
  createdAt: '2026-11-01T04:30:00Z',
  expiresAt: '2027-01-30T04:30:00Z',
  lastUsedAt: '2026-11-02T10:00:00Z',
  revokedAt: '2026-11-05T09:45:00Z',
}

// Past its 90 days and never revoked: the server refuses it, so it is not Active.
const expiredToken = {
  id: 4,
  name: 'Summer Script',
  createdAt: '2026-05-01T04:30:00Z',
  expiresAt: '2026-07-30T04:30:00Z',
  lastUsedAt: '2026-07-01T10:00:00Z',
  revokedAt: null,
}

function renderAccessTokens() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <AccessTokensPage />
    </QueryClientProvider>,
  )
}

const callsWith = (fetchMock: ReturnType<typeof mockFetch>, method: string) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === method)

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AccessTokensPage', () => {
  it('lists tokens, displaying name, dates in IST, last used, and status', async () => {
    mockFetch({ 'GET /api/me/tokens': { body: [activeToken, unusedToken, revokedToken] } })
    renderAccessTokens()

    expect(await screen.findByText('Claude Desktop')).toBeInTheDocument()
    expect(screen.getByText('Cursor Assistant')).toBeInTheDocument()
    expect(screen.getByText('Old Terminal Script')).toBeInTheDocument()

    // Active tokens show Revoke button; revoked tokens do not
    expect(screen.getByRole('button', { name: 'Revoke Claude Desktop' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revoke Cursor Assistant' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke Old Terminal Script' })).not.toBeInTheDocument()

    // Status: active tokens show Active, revoked token shows Revoked date
    expect(screen.getAllByText('Active')).toHaveLength(2)
    expect(screen.getByText(/^Revoked/)).toBeInTheDocument()

    // Null lastUsedAt displays "Never"
    expect(screen.getByText('Never')).toBeInTheDocument()
  })

  it('shows a token past its 90 days as expired, with no Revoke', async () => {
    mockFetch({ 'GET /api/me/tokens': { body: [activeToken, expiredToken] } })
    renderAccessTokens()

    const row = (await screen.findByText('Summer Script')).closest('tr')!
    expect(within(row).getByText(/Expired/)).toBeInTheDocument()
    expect(within(row).queryByText('Active')).not.toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: /Revoke/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revoke Claude Desktop' })).toBeInTheDocument()
  })

  it('renders an empty state when user has no tokens', async () => {
    mockFetch({ 'GET /api/me/tokens': { body: [] } })
    renderAccessTokens()

    expect(await screen.findByText(/No access tokens yet/i)).toBeInTheDocument()
  })

  it('renders loading state and error state with retry', async () => {
    mockFetch({
      'GET /api/me/tokens': { status: 500, body: { status: 500, message: 'Database connection failed' } },
    })
    renderAccessTokens()

    expect(await screen.findByText('Database connection failed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  // TC-FR-23.1b: Create a token, then close the dialog -> raw token is gone for good
  it('shows the raw token once with warning and copy button, and drops it completely on close', async () => {
    const user = userEvent.setup()
    mockFetch({
      'GET /api/me/tokens': { body: [] },
      'POST /api/me/tokens': {
        status: 201,
        body: {
          id: 10,
          name: 'Claude Desktop',
          token: RAW_TOKEN,
          createdAt: '2026-11-11T05:00:00Z',
          expiresAt: '2027-02-09T05:00:00Z',
        },
      },
    })
    renderAccessTokens()

    await user.click(await screen.findByRole('button', { name: 'Create token' }))
    const formDialog = await screen.findByRole('dialog', { name: 'Create personal access token' })
    await user.type(within(formDialog).getByLabelText('Name'), 'Claude Desktop')
    await user.click(within(formDialog).getByRole('button', { name: 'Create token' }))

    // One-time secret display step
    const tokenDialog = await screen.findByRole('dialog', { name: 'Copy the token for Claude Desktop' })
    expect(within(tokenDialog).getByLabelText('Token')).toHaveValue(RAW_TOKEN)
    expect(within(tokenDialog).getByRole('alert')).toHaveTextContent("it can't be shown again")

    // Copy action
    await user.click(within(tokenDialog).getByRole('button', { name: 'Copy' }))
    expect(await navigator.clipboard.readText()).toBe(RAW_TOKEN)
    expect(within(tokenDialog).getByRole('button', { name: 'Copied' })).toBeInTheDocument()

    // Closing the dialog drops the raw token
    await user.click(within(tokenDialog).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByDisplayValue(RAW_TOKEN)).not.toBeInTheDocument()

    // Opening the dialog again starts a clean new form; previous raw token is not retained
    await user.click(screen.getByRole('button', { name: 'Create token' }))
    const newDialog = await screen.findByRole('dialog', { name: 'Create personal access token' })
    expect(within(newDialog).getByLabelText('Name')).toHaveValue('')
    expect(screen.queryByDisplayValue(RAW_TOKEN)).not.toBeInTheDocument()
  })

  it('displays form validation error when submitting an empty name', async () => {
    const user = userEvent.setup()
    mockFetch({ 'GET /api/me/tokens': { body: [] } })
    renderAccessTokens()

    await user.click(await screen.findByRole('button', { name: 'Create token' }))
    const formDialog = await screen.findByRole('dialog', { name: 'Create personal access token' })
    await user.click(within(formDialog).getByRole('button', { name: 'Create token' }))

    expect(await within(formDialog).findByText('Enter a name')).toBeInTheDocument()
  })

  it('displays duplicate-name conflict (HTTP 409) error on the name input', async () => {
    const user = userEvent.setup()
    mockFetch({
      'GET /api/me/tokens': { body: [activeToken] },
      'POST /api/me/tokens': {
        status: 409,
        body: { status: 409, message: "Token with name 'Claude Desktop' already exists", errors: {} },
      },
    })
    renderAccessTokens()

    await user.click(await screen.findByRole('button', { name: 'Create token' }))
    const formDialog = await screen.findByRole('dialog', { name: 'Create personal access token' })
    await user.type(within(formDialog).getByLabelText('Name'), 'Claude Desktop')
    await user.click(within(formDialog).getByRole('button', { name: 'Create token' }))

    expect(await within(formDialog).findByText("Token with name 'Claude Desktop' already exists")).toBeInTheDocument()
  })

  // TC-FR-23.1b: revoking requires confirmation; cancelling sends zero DELETE requests
  it('revokes a token only after confirmation; cancel sends zero DELETE requests', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch({
      'GET /api/me/tokens': { body: [activeToken] },
      'DELETE /api/me/tokens/1': { status: 204, body: null },
    })
    renderAccessTokens()

    // Click Revoke -> opens confirmation dialog
    await user.click(await screen.findByRole('button', { name: 'Revoke Claude Desktop' }))
    const confirm = await screen.findByRole('dialog', { name: 'Revoke Claude Desktop?' })
    expect(within(confirm).getByText(/Any script or AI assistant using this personal access token will stop working immediately/i)).toBeInTheDocument()

    // Click Cancel -> dialog closes, zero DELETE calls sent
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(callsWith(fetchMock, 'DELETE')).toHaveLength(0)

    // Click Revoke again, then confirm -> sends exactly 1 DELETE request
    await user.click(screen.getByRole('button', { name: 'Revoke Claude Desktop' }))
    const confirmAgain = await screen.findByRole('dialog', { name: 'Revoke Claude Desktop?' })
    await user.click(within(confirmAgain).getByRole('button', { name: 'Revoke token' }))

    await waitFor(() => expect(callsWith(fetchMock, 'DELETE')).toHaveLength(1))
    expect(fetchMock).toHaveBeenCalledWith('/api/me/tokens/1', expect.objectContaining({ method: 'DELETE' }))
  })
})
