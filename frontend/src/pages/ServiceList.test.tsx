import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { mockFetch, payments } from '../test/fetchMock'
import ServiceList from './ServiceList'

function renderList(canEdit: boolean) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ServiceList canEdit={canEdit} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

test('lists services with owner, criticality and targets', async () => {
  mockFetch({ 'GET /api/services': { body: [payments] } })
  renderList(false)

  expect(await screen.findByRole('cell', { name: 'Payments' })).toBeInTheDocument()
  expect(screen.getByText('Dev Admin')).toBeInTheDocument()
  expect(screen.getByText('CRITICAL')).toBeInTheDocument()
  expect(screen.getByText('15 min / 4 h')).toBeInTheDocument()
  expect(screen.getByText('1 d / 7 d')).toBeInTheDocument()
})

test('only an admin sees the new and edit links', async () => {
  mockFetch({ 'GET /api/services': { body: [payments] } })
  renderList(false)
  await screen.findByRole('cell', { name: 'Payments' })
  expect(screen.queryByRole('link', { name: 'New service' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /Edit/ })).not.toBeInTheDocument()
})

test('admin sees links to create and edit', async () => {
  mockFetch({ 'GET /api/services': { body: [payments] } })
  renderList(true)
  await screen.findByRole('cell', { name: 'Payments' })
  expect(screen.getByRole('link', { name: 'New service' })).toHaveAttribute('href', '/services/new')
  expect(screen.getByRole('link', { name: 'Edit Payments' })).toHaveAttribute('href', '/services/1/edit')
})

test('a failed request says so', async () => {
  mockFetch({ 'GET /api/services': { status: 401, body: { status: 401, message: 'Unauthorized', errors: {} } } })
  renderList(false)
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not load services: Unauthorized')
})
