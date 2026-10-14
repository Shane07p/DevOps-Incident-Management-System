import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { defaultTargets, mockFetch, payments, users } from '../test/fetchMock'
import ServiceForm from './ServiceForm'

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/services" element={<p>Service list</p>} />
          <Route path="/services/new" element={<ServiceForm />} />
          <Route path="/services/:id/edit" element={<ServiceForm />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const input = (id: string) => document.getElementById(id) as HTMLInputElement

afterEach(() => vi.unstubAllGlobals())

test('a new service starts with the default response targets', async () => {
  mockFetch({ 'GET /api/services/default-targets': { body: defaultTargets }, 'GET /api/users': { body: users } })
  renderAt('/services/new')

  await screen.findByText('Register a service')
  expect(input('targets.SEV1.responseMinutes').value).toBe('15')
  expect(input('targets.SEV4.responseMinutes').value).toBe('1440')
  expect(input('targets.SEV1.resolutionMinutes').value).toBe('')
})

test('submitting an incomplete form shows errors and sends nothing', async () => {
  const fetch = mockFetch({
    'GET /api/services/default-targets': { body: defaultTargets },
    'GET /api/users': { body: users },
  })
  renderAt('/services/new')
  await screen.findByText('Register a service')

  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(screen.getByText('Name is required')).toBeInTheDocument()
  expect(screen.getByText('Owner is required')).toBeInTheDocument()
  expect(input('name')).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getAllByText('Resolution target is required')).toHaveLength(4)
  expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})

test('a valid form posts the service and returns to the list', async () => {
  let posted: unknown
  mockFetch({
    'GET /api/services/default-targets': { body: defaultTargets },
    'GET /api/users': { body: users },
    'POST /api/services': (init) => {
      posted = JSON.parse(String(init?.body))
      return { status: 201, body: payments }
    },
    'GET /api/services': { body: [payments] },
  })
  renderAt('/services/new')
  await screen.findByText('Register a service')

  await userEvent.type(input('name'), 'Payments')
  await userEvent.selectOptions(input('ownerId'), 'Dev Engineer')
  await userEvent.selectOptions(input('criticality'), 'CRITICAL')
  for (const [severity, minutes] of [['SEV1', '240'], ['SEV2', '480'], ['SEV3', '2880'], ['SEV4', '10080']]) {
    await userEvent.type(input(`targets.${severity}.resolutionMinutes`), minutes)
  }
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  await screen.findByText('Service list')
  expect(posted).toEqual({
    name: 'Payments',
    description: null,
    ownerId: 2,
    criticality: 'CRITICAL',
    targets: {
      SEV1: { responseMinutes: 15, resolutionMinutes: 240 },
      SEV2: { responseMinutes: 60, resolutionMinutes: 480 },
      SEV3: { responseMinutes: 240, resolutionMinutes: 2880 },
      SEV4: { responseMinutes: 1440, resolutionMinutes: 10080 },
    },
  })
})

test('a duplicate name from the server shows on the name field', async () => {
  mockFetch({
    'GET /api/services/1': { body: payments },
    'GET /api/users': { body: users },
    'PUT /api/services/1': { status: 409, body: { status: 409, message: 'A service named Search already exists', errors: {} } },
  })
  renderAt('/services/1/edit')
  await screen.findByText('Edit service')
  expect(input('name').value).toBe('Payments')
  expect(input('targets.SEV4.resolutionMinutes').value).toBe('10080')

  await userEvent.clear(input('name'))
  await userEvent.type(input('name'), 'Search')
  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(screen.getByText('A service named Search already exists')).toBeInTheDocument())
  expect(input('name')).toHaveAttribute('aria-invalid', 'true')
})

test('server field errors appear next to the field they name', async () => {
  mockFetch({
    'GET /api/services/1': { body: payments },
    'GET /api/users': { body: users },
    'PUT /api/services/1': {
      status: 400,
      body: { status: 400, message: 'Validation failed', errors: { ownerId: 'Owner not found' } },
    },
  })
  renderAt('/services/1/edit')
  await screen.findByText('Edit service')

  await userEvent.click(screen.getByRole('button', { name: 'Save' }))

  expect(await screen.findByText('Owner not found')).toBeInTheDocument()
  expect(input('ownerId')).toHaveAttribute('aria-invalid', 'true')
})
