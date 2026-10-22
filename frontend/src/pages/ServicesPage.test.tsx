import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/api/schemas'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { RequireAuth } from '@/auth/RequireAuth'
import { mockFetch, payments, users } from '@/test/fetchMock'
import { ServicesPage } from './ServicesPage'

const signedIn: Record<Role, unknown> = {
  ADMIN: users[0],
  ENGINEER: users[1],
  VIEWER: { id: 3, username: 'viewer', displayName: 'Dev Viewer', role: 'VIEWER' },
}

type Routes = Parameters<typeof mockFetch>[0]

// Renders behind RequireAuth, as the app does, so the page only appears once the role is known.
function renderServices(role: Role, routes: Routes = {}) {
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: signedIn[role] },
    'GET /api/services': { body: [payments] },
    'GET /api/users': { body: users },
    ...routes,
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/services']}>
          <Routes>
            <Route element={<RequireAuth />}>
              <Route path="/services" element={<ServicesPage />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  return fetchMock
}

const callsWith = (fetchMock: ReturnType<typeof mockFetch>, method: string) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === method)

async function openNewServiceDialog() {
  await userEvent.click(await screen.findByRole('button', { name: 'New service' }))
  return screen.findByRole('dialog', { name: 'New service' })
}

async function choose(dialog: HTMLElement, label: string, option: string) {
  await userEvent.click(within(dialog).getByLabelText(label))
  await userEvent.click(await screen.findByRole('option', { name: option }))
}

async function setMinutes(dialog: HTMLElement, label: string, minutes: string) {
  const input = within(dialog).getByLabelText(label)
  await userEvent.clear(input)
  await userEvent.type(input, minutes)
}

// Fills everything except the resolution targets, which each test sets itself.
async function fillDetails(dialog: HTMLElement) {
  await userEvent.type(within(dialog).getByLabelText('Name'), 'Checkout')
  await choose(dialog, 'Owner', 'Dev Engineer')
  await choose(dialog, 'Criticality', 'high')
}

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('ServicesPage', () => {
  it.each<Role>(['VIEWER', 'ENGINEER'])('shows a %s each service with its owner and criticality, without New or Edit', async (role) => {
    renderServices(role)

    const row = (await screen.findByText('Payments')).closest('tr')!
    expect(within(row).getByText('Dev Admin')).toBeInTheDocument()
    expect(within(row).getByText('critical')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New service' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit Payments' })).not.toBeInTheDocument()
  })

  it('fills in the default response targets, leaves resolution to the Admin, and uses field names as ids', async () => {
    renderServices('ADMIN')
    const dialog = await openNewServiceDialog()

    for (const [severity, minutes] of [['SEV1', '15'], ['SEV2', '60'], ['SEV3', '240'], ['SEV4', '1440']]) {
      expect(within(dialog).getByLabelText(`${severity} response target, in minutes`)).toHaveValue(Number(minutes))
      expect(within(dialog).getByLabelText(`${severity} resolution target, in minutes`)).toHaveValue(null)
    }
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('id', 'name')
    expect(within(dialog).getByLabelText('Owner')).toHaveAttribute('id', 'ownerId')
    expect(within(dialog).getByLabelText('Criticality')).toHaveAttribute('id', 'criticality')
    expect(within(dialog).getByLabelText('SEV2 resolution target, in minutes')).toHaveAttribute('id', 'targets.SEV2.resolutionMinutes')
  })

  it('sends a new service with numeric targets', async () => {
    const fetchMock = renderServices('ADMIN', {
      'POST /api/services': { status: 201, body: { ...payments, id: 2, name: 'Checkout' } },
    })
    const dialog = await openNewServiceDialog()

    await fillDetails(dialog)
    for (const [severity, minutes] of [['SEV1', '240'], ['SEV2', '480'], ['SEV3', '2880'], ['SEV4', '10080']]) {
      await setMinutes(dialog, `${severity} resolution target, in minutes`, minutes)
    }
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add service' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [[url, init]] = callsWith(fetchMock, 'POST')
    expect(url).toBe('/api/services')
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Checkout',
      description: '',
      ownerId: 2,
      criticality: 'HIGH',
      targets: {
        SEV1: { responseMinutes: 15, resolutionMinutes: 240 },
        SEV2: { responseMinutes: 60, resolutionMinutes: 480 },
        SEV3: { responseMinutes: 240, resolutionMinutes: 2880 },
        SEV4: { responseMinutes: 1440, resolutionMinutes: 10080 },
      },
    })
  })

  // TC-FR-14.1i: a resolution target below its response target is refused before anything is sent.
  it('refuses a new service whose resolution target is below its response target, and sends nothing', async () => {
    const fetchMock = renderServices('ADMIN', { 'POST /api/services': { status: 201, body: payments } })
    const dialog = await openNewServiceDialog()

    await fillDetails(dialog)
    for (const [severity, minutes] of [['SEV1', '240'], ['SEV2', '59'], ['SEV3', '2880'], ['SEV4', '10080']]) {
      await setMinutes(dialog, `${severity} resolution target, in minutes`, minutes)
    }
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add service' }))

    const message = "Resolution target can't be shorter than the response target"
    expect(await within(dialog).findByText(message)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('SEV2 resolution target, in minutes')).toHaveAccessibleDescription(message)
    expect(within(dialog).getAllByText(message)).toHaveLength(1)
    expect(callsWith(fetchMock, 'POST')).toHaveLength(0)
  })

  // TC-FR-14.1i, on edit: the same rule holds when an Admin lowers an existing target.
  it('refuses an edit that puts a resolution target below its response target, and sends nothing', async () => {
    const fetchMock = renderServices('ADMIN', { 'PUT /api/services/1': { body: payments } })
    await userEvent.click(await screen.findByRole('button', { name: 'Edit Payments' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit Payments' })
    expect(within(dialog).getByLabelText('Name')).toHaveValue('Payments')
    expect(within(dialog).getByLabelText('SEV1 resolution target, in minutes')).toHaveValue(240)

    await setMinutes(dialog, 'SEV1 resolution target, in minutes', '14')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    const message = "Resolution target can't be shorter than the response target"
    expect(await within(dialog).findByText(message)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('SEV1 resolution target, in minutes')).toHaveAccessibleDescription(message)
    expect(callsWith(fetchMock, 'PUT')).toHaveLength(0)
  })

  it('shows an empty submit as field errors and sends nothing', async () => {
    const fetchMock = renderServices('ADMIN')
    const dialog = await openNewServiceDialog()

    await userEvent.click(within(dialog).getByRole('button', { name: 'Add service' }))

    expect(await within(dialog).findByText('Enter a name')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Owner')).toHaveAccessibleDescription('Choose an owner')
    expect(within(dialog).getByLabelText('Criticality')).toHaveAccessibleDescription('Choose a criticality')
    expect(within(dialog).getByLabelText('SEV4 resolution target, in minutes')).toHaveAccessibleDescription('Enter minutes')
    expect(callsWith(fetchMock, 'POST')).toHaveLength(0)
  })

  it('puts the server field errors under the matching inputs', async () => {
    renderServices('ADMIN', {
      'PUT /api/services/1': {
        status: 400,
        body: {
          status: 400,
          message: 'Validation failed',
          errors: { 'targets.SEV3.responseMinutes': 'Must be at least 1 minute', ownerId: 'Owner not found' },
        },
      },
    })
    await userEvent.click(await screen.findByRole('button', { name: 'Edit Payments' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit Payments' })

    await userEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByText('Must be at least 1 minute')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('SEV3 response target, in minutes')).toHaveAccessibleDescription('Must be at least 1 minute')
    expect(within(dialog).getByLabelText('Owner')).toHaveAccessibleDescription('Owner not found')
  })

  it('shows a duplicate name under the name field', async () => {
    renderServices('ADMIN', {
      'PUT /api/services/1': { status: 409, body: { status: 409, message: 'A service named Payments already exists' } },
    })
    await userEvent.click(await screen.findByRole('button', { name: 'Edit Payments' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit Payments' })

    await userEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByText('A service named Payments already exists')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveAccessibleDescription('A service named Payments already exists')
  })
})
