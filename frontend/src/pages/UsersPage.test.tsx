import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { devOrg, mockFetch, users } from '@/test/fetchMock'
import { UsersPage } from './UsersPage'

function renderUsers() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <UsersPage />
      </AuthProvider>
    </QueryClientProvider>,
  )
}

async function openCreateDialog() {
  await userEvent.click(await screen.findByRole('button', { name: 'Add member' }))
  return screen.findByRole('dialog', { name: 'Add a member' })
}

async function fillCreateForm(dialog: HTMLElement, password: string) {
  await userEvent.type(within(dialog).getByLabelText('Email'), 'asha@acme.test')
  await userEvent.type(within(dialog).getByLabelText('Display name'), 'Asha')
  await userEvent.type(within(dialog).getByLabelText('Password'), password)
}

const postsTo = (fetchMock: ReturnType<typeof mockFetch>, method: string) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === method)

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

// Signed in as Dev Admin (users[0]), so the page knows which row is yours.
const signedIn = { 'GET /api/auth/me': { body: { user: users[0], org: devOrg } } }
const withDisabled = [users[0], users[1], { id: 3, email: 'viewer@settle.local', displayName: 'Dev Viewer', role: 'VIEWER', enabled: false }]

describe('UsersPage', () => {
  it('lists every member with their email and role', async () => {
    mockFetch({ 'GET /api/users': { body: users } })
    renderUsers()

    expect(await screen.findByText('Dev Admin')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Members' })).toBeInTheDocument()
    expect(screen.getByText('engineer@settle.local')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Role of Dev Engineer' })).toHaveTextContent('Engineer')
  })

  // TC-US-01i: a password one character too short is refused before anything is sent.
  it('refuses a 7-character password and sends nothing', async () => {
    const fetchMock = mockFetch({ 'GET /api/users': { body: users } })
    renderUsers()
    const dialog = await openCreateDialog()

    await fillCreateForm(dialog, 'seven77')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add member' }))

    expect(await within(dialog).findByText('Use at least 8 characters')).toBeInTheDocument()
    expect(postsTo(fetchMock, 'POST')).toHaveLength(0)
  })

  it('adds a member by email', async () => {
    const fetchMock = mockFetch({
      'GET /api/users': { body: users },
      'POST /api/users': { status: 201, body: { id: 4, email: 'asha@acme.test', displayName: 'Asha', role: 'ENGINEER' } },
    })
    renderUsers()
    const dialog = await openCreateDialog()

    await fillCreateForm(dialog, 'eight888')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add member' }))

    await waitFor(() => expect(postsTo(fetchMock, 'POST')).toHaveLength(1))
    const [[url, init]] = postsTo(fetchMock, 'POST')
    expect(url).toBe('/api/users')
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'asha@acme.test', displayName: 'Asha', password: 'eight888', role: 'ENGINEER' })
  })

  // TC-US-01j: the server's 409 for a taken email lands on the email field.
  it('shows a taken email under the email field', async () => {
    mockFetch({
      'GET /api/users': { body: users },
      'POST /api/users': { status: 409, body: { status: 409, message: 'An account with this email already exists' } },
    })
    renderUsers()
    const dialog = await openCreateDialog()

    await fillCreateForm(dialog, 'eight888')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add member' }))

    expect(await within(dialog).findByText('An account with this email already exists')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Email')).toHaveAccessibleDescription('An account with this email already exists')
  })

  // TC-US-01k: a role change waits for confirmation; cancelling sends nothing.
  it('asks before changing a role, and only sends it once confirmed', async () => {
    const fetchMock = mockFetch({
      'GET /api/users': { body: users },
      'PUT /api/users/2/role': { body: { ...users[1], role: 'ADMIN' } },
    })
    renderUsers()

    await userEvent.click(await screen.findByRole('combobox', { name: 'Role of Dev Engineer' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Admin' }))
    const confirm = await screen.findByRole('dialog', { name: 'Change role?' })
    expect(confirm).toHaveTextContent('Dev Engineer becomes Admin')

    await userEvent.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    expect(postsTo(fetchMock, 'PUT')).toHaveLength(0)

    await userEvent.click(screen.getByRole('combobox', { name: 'Role of Dev Engineer' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Admin' }))
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Change role?' })).getByRole('button', { name: 'Change role' }))

    const [[url, init]] = postsTo(fetchMock, 'PUT')
    expect(url).toBe('/api/users/2/role')
    expect(JSON.parse(String(init?.body))).toEqual({ role: 'ADMIN' })
  })

  // TC-FR-01.6i: a member is disabled only after confirmation; your own row has no Disable.
  it('asks before disabling a member, and offers no Disable on your own row', async () => {
    writeToken('test-token')
    const fetchMock = mockFetch({
      ...signedIn,
      'GET /api/users': { body: users },
      'PUT /api/users/2/enabled': { body: { ...users[1], enabled: false } },
    })
    renderUsers()

    // Wait until the page knows who is signed in, then look for a Disable on that row.
    expect(await screen.findByText('(you)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Disable Dev Admin' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Disable Dev Engineer' }))
    const confirm = await screen.findByRole('dialog', { name: 'Disable Dev Engineer?' })
    expect(confirm).toHaveTextContent('signed out everywhere')

    await userEvent.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    expect(postsTo(fetchMock, 'PUT')).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: 'Disable Dev Engineer' }))
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Disable Dev Engineer?' })).getByRole('button', { name: 'Disable' }))

    await waitFor(() => expect(postsTo(fetchMock, 'PUT')).toHaveLength(1))
    const [[url, init]] = postsTo(fetchMock, 'PUT')
    expect(url).toBe('/api/users/2/enabled')
    expect(JSON.parse(String(init?.body))).toEqual({ enabled: false })
  })

  it('marks a disabled member and enables them again once confirmed', async () => {
    writeToken('test-token')
    const fetchMock = mockFetch({
      ...signedIn,
      'GET /api/users': { body: withDisabled },
      'PUT /api/users/3/enabled': { body: { ...withDisabled[2], enabled: true } },
    })
    renderUsers()

    expect(await screen.findByText('Disabled')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Disable Dev Viewer' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Enable Dev Viewer' }))
    const confirm = await screen.findByRole('dialog', { name: 'Enable Dev Viewer?' })
    expect(confirm).toHaveTextContent('can sign in again')
    await userEvent.click(within(confirm).getByRole('button', { name: 'Enable' }))

    await waitFor(() => expect(postsTo(fetchMock, 'PUT')).toHaveLength(1))
    expect(JSON.parse(String(postsTo(fetchMock, 'PUT')[0][1]?.body))).toEqual({ enabled: true })
  })

  it("shows the server's reason when it refuses the change", async () => {
    writeToken('test-token')
    mockFetch({
      ...signedIn,
      'GET /api/users': { body: users },
      'PUT /api/users/2/enabled': { status: 409, body: { status: 409, message: 'An org needs at least one Admin' } },
    })
    renderUsers()

    await userEvent.click(await screen.findByRole('button', { name: 'Disable Dev Engineer' }))
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Disable Dev Engineer?' })).getByRole('button', { name: 'Disable' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('An org needs at least one Admin')
    expect(screen.getByRole('dialog', { name: 'Disable Dev Engineer?' })).toBeInTheDocument()
  })
})
