import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
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
  await userEvent.click(await screen.findByRole('button', { name: 'Add user' }))
  return screen.findByRole('dialog', { name: 'Add a user' })
}

async function fillCreateForm(dialog: HTMLElement, password: string) {
  await userEvent.type(within(dialog).getByLabelText('Username'), 'asha')
  await userEvent.type(within(dialog).getByLabelText('Display name'), 'Asha')
  await userEvent.type(within(dialog).getByLabelText('Password'), password)
}

const postsTo = (fetchMock: ReturnType<typeof mockFetch>, method: string) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === method)

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('UsersPage', () => {
  it('lists every user with their username and role', async () => {
    mockFetch({ 'GET /api/users': { body: users } })
    renderUsers()

    expect(await screen.findByText('Dev Admin')).toBeInTheDocument()
    expect(screen.getByText('engineer')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Role of Dev Engineer' })).toHaveTextContent('Engineer')
  })

  // TC-US-01i: a password one character too short is refused before anything is sent.
  it('refuses a 7-character password and sends nothing', async () => {
    const fetchMock = mockFetch({ 'GET /api/users': { body: users } })
    renderUsers()
    const dialog = await openCreateDialog()

    await fillCreateForm(dialog, 'seven77')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add user' }))

    expect(await within(dialog).findByText('Use at least 8 characters')).toBeInTheDocument()
    expect(postsTo(fetchMock, 'POST')).toHaveLength(0)
  })

  // TC-US-01j: the server's 409 for a taken username lands on the username field.
  it('shows a duplicate username under the username field', async () => {
    mockFetch({
      'GET /api/users': { body: users },
      'POST /api/users': { status: 409, body: { status: 409, message: 'That username is already taken' } },
    })
    renderUsers()
    const dialog = await openCreateDialog()

    await fillCreateForm(dialog, 'eight888')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add user' }))

    expect(await within(dialog).findByText('That username is already taken')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Username')).toHaveAccessibleDescription('That username is already taken')
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
