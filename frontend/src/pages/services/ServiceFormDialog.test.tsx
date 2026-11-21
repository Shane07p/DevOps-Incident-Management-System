import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { clearToken, writeToken } from '@/api/token'
import { serviceSchema, type Service } from '@/api/services'
import { AuthProvider } from '@/auth/AuthProvider'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'
import { ServiceFormDialog } from './ServiceFormDialog'

afterEach(() => { clearToken(); vi.unstubAllGlobals() })

it('does not reset typed values or silently advance the loaded version on a background refetch', async () => {
  const user = userEvent.setup()
  writeToken('test-token')
  const fetchMock = mockFetch({
    'GET /api/auth/me': { body: { user: users[0], org: devOrg } },
    'GET /api/users': { body: users },
    'GET /api/service-map': { body: { nodes: [], edges: [] } },
    'PUT /api/services/1': { status: 409, body: { message: 'Stale version' } },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const original = serviceSchema.parse(payments)
  const view = (service: Service) => (
    <QueryClientProvider client={client}><AuthProvider><MemoryRouter>
      <ServiceFormDialog service={service} open onOpenChange={() => {}} />
    </MemoryRouter></AuthProvider></QueryClientProvider>
  )
  const rendered = render(view(original))
  const name = await screen.findByLabelText('Name')
  await user.clear(name)
  await user.type(name, 'My typed name')
  rendered.rerender(view({ ...original, version: 3, description: 'Fresh server description' }))
  expect(name).toHaveValue('My typed name')
  await user.click(screen.getByRole('button', { name: 'Save changes' }))
  await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true))
  const call = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
  expect(JSON.parse(String(call[1]?.body))).toMatchObject({ name: 'My typed name', version: 0 })
  expect(await screen.findByText('This service was changed by someone else. Reload to see the latest.')).toBeInTheDocument()
})
