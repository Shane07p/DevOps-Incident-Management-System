import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '@/auth/AuthProvider'
import { mockFetch, users } from '@/test/fetchMock'
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
  vi.unstubAllGlobals()
})

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
})
