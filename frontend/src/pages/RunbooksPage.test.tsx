
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/api/schemas'
import { clearToken, writeToken } from '@/api/token'
import { AuthProvider } from '@/auth/AuthProvider'
import { RequireAuth } from '@/auth/RequireAuth'
import { devOrg, mockFetch, payments, users } from '@/test/fetchMock'
import { RunbooksPage } from './RunbooksPage'

const signedIn: Record<Role, unknown> = {
  ADMIN: users[0],
  ENGINEER: users[1],
  VIEWER: {
    id: 3,
    email: 'viewer@settle.local',
    displayName: 'Dev Viewer',
    role: 'VIEWER',
  },
}

type MockRoutes = Parameters<typeof mockFetch>[0]

const sampleRunbook = {
  id: 10,
  title: 'Restart payments service',
  steps: [
    'Check the service logs',
    'Restart the service',
    'Verify service health',
  ],
}

function renderRunbooks(
  role: Role,
  routes: MockRoutes = {},
) {
  writeToken('test-token')

  const fetchMock = mockFetch({
    'GET /api/auth/me': {
      body: { user: signedIn[role], org: devOrg },
    },
    'GET /api/services': {
      body: [payments],
    },
    'GET /api/services/1/runbooks': {
      body: [sampleRunbook],
    },
    ...routes,
  })

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/runbooks']}>
          <Routes>
            <Route element={<RequireAuth />}>
              <Route
                path="/runbooks"
                element={<RunbooksPage />}
              />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )

  return fetchMock
}

const callsWith = (
  fetchMock: ReturnType<typeof mockFetch>,
  method: string,
) =>
  fetchMock.mock.calls.filter(
    ([, init]) => init?.method === method,
  )

afterEach(() => {
  clearToken()
  vi.unstubAllGlobals()
})

describe('RunbooksPage', () => {
  it('shows runbooks with their steps in the correct order', async () => {
    renderRunbooks('VIEWER')

    await screen.findByRole('heading', { name: 'Restart payments service' })

    const firstStep = screen.getByText('Check the service logs')
    const secondStep = screen.getByText('Restart the service')
    const thirdStep = screen.getByText('Verify service health')

    expect(
      firstStep.compareDocumentPosition(secondStep) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()

    expect(
      secondStep.compareDocumentPosition(thirdStep) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('allows Viewers to read but not edit runbooks', async () => {
    renderRunbooks('VIEWER')

    expect(
      await screen.findByRole('heading', { name: 'Restart payments service' }),
    ).toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'New runbook' }),
    ).not.toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'Edit' }),
    ).not.toBeInTheDocument()

    expect(
      screen.queryByRole('button', { name: 'Delete' }),
    ).not.toBeInTheDocument()
  })

  it.each<Role>(['ADMIN', 'ENGINEER'])(
    'allows %s to create runbooks with ordered steps',
    async (role) => {
      const fetchMock = renderRunbooks(role, {
        'POST /api/services/1/runbooks': {
          status: 201,
          body: {
            id: 11,
            title: 'Fix database',
            steps: [
              'Check database',
              'Restart database',
            ],
          },
        },
      })

      await userEvent.click(
        await screen.findByRole('button', {
          name: 'New runbook',
        }),
      )

      await userEvent.type(
        screen.getByLabelText('Runbook title'),
        'Fix database',
      )

      await userEvent.type(
        screen.getByLabelText('Step 1'),
        'Check database',
      )

      await userEvent.click(
        screen.getByRole('button', { name: 'Add step' }),
      )

      await userEvent.type(
        screen.getByLabelText('Step 2'),
        'Restart database',
      )

      await userEvent.click(
        screen.getByRole('button', { name: 'Save runbook' }),
      )

      await waitFor(() => {
        expect(callsWith(fetchMock, 'POST')).toHaveLength(1)
      })

      const [[url, init]] = callsWith(fetchMock, 'POST')

      expect(url).toBe('/api/services/1/runbooks')

      expect(JSON.parse(String(init?.body))).toEqual({
        title: 'Fix database',
        steps: [
          'Check database',
          'Restart database',
        ],
      })
    },
  )

  it('keeps the new order when steps are moved', async () => {
    const fetchMock = renderRunbooks('ENGINEER', {
      'PUT /api/runbooks/10': {
        body: sampleRunbook,
      },
    })

    await userEvent.click(
      await screen.findByRole('button', {
        name: 'Edit',
      }),
    )

    expect(screen.getByLabelText('Step 1')).toHaveValue(
      'Check the service logs',
    )

    await userEvent.click(
      screen.getAllByRole('button', { name: 'Up' })[1],
    )

    expect(screen.getByLabelText('Step 1')).toHaveValue(
      'Restart the service',
    )

    expect(screen.getByLabelText('Step 2')).toHaveValue(
      'Check the service logs',
    )

    await userEvent.click(
      screen.getByRole('button', { name: 'Save runbook' }),
    )

    await waitFor(() => {
      expect(callsWith(fetchMock, 'PUT')).toHaveLength(1)
    })

    const [[url, init]] = callsWith(fetchMock, 'PUT')

    expect(url).toBe('/api/runbooks/10')

    expect(JSON.parse(String(init?.body))).toEqual({
      title: 'Restart payments service',
      steps: [
        'Restart the service',
        'Check the service logs',
        'Verify service health',
      ],
    })
  })

  it('rejects an empty title and empty steps', async () => {
    const fetchMock = renderRunbooks('ADMIN')

    await userEvent.click(
      await screen.findByRole('button', {
        name: 'New runbook',
      }),
    )

    await userEvent.click(
      screen.getByRole('button', { name: 'Save runbook' }),
    )

    expect(
      screen.getByRole('alert'),
    ).toHaveTextContent('Enter a runbook title.')

    await userEvent.type(
      screen.getByLabelText('Runbook title'),
      'Recovery',
    )

    await userEvent.click(
      screen.getByRole('button', { name: 'Save runbook' }),
    )

    expect(
      screen.getByRole('alert'),
    ).toHaveTextContent('Add at least one non-empty step.')

    expect(callsWith(fetchMock, 'POST')).toHaveLength(0)
  })

  it('allows an Admin to delete a runbook after confirmation', async () => {
    const fetchMock = renderRunbooks('ADMIN', {
      'DELETE /api/runbooks/10': {
        status: 200,
        body: null,
      },
    })

    await userEvent.click(
      await screen.findByRole('button', {
        name: 'Delete',
      }),
    )

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('Restart payments service')
    expect(callsWith(fetchMock, 'DELETE')).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete runbook' }))

    await waitFor(() => {
      expect(callsWith(fetchMock, 'DELETE')).toHaveLength(1)
    })

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('cancels deletion without sending a request', async () => {
    const fetchMock = renderRunbooks('ENGINEER')
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(callsWith(fetchMock, 'DELETE')).toHaveLength(0)
  })

  it('limits title and step text to the backend lengths', async () => {
    const user = userEvent.setup()
    renderRunbooks('ENGINEER')
    await user.click(await screen.findByRole('button', { name: 'New runbook' }))
    await user.click(screen.getByLabelText('Runbook title'))
    await user.paste('t'.repeat(201))
    await user.click(screen.getByLabelText('Step 1'))
    await user.paste('s'.repeat(2001))

    expect(screen.getByLabelText('Runbook title')).toHaveValue('t'.repeat(200))
    expect(screen.getByLabelText('Step 1')).toHaveValue('s'.repeat(2000))
  })

  it('stops at 50 steps, explains the limit, and allows adding after removal', async () => {
    renderRunbooks('ADMIN', {
      'GET /api/services/1/runbooks': {
        body: [{ ...sampleRunbook, steps: Array.from({ length: 49 }, (_, index) => `Step ${index + 1}`) }],
      },
    })
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: 'Add step' }))

    expect(screen.getByRole('button', { name: 'Add step' })).toBeDisabled()
    expect(screen.getByText('A runbook can have at most 50 steps.')).toBeInTheDocument()
    expect(screen.getByLabelText('Step 50')).toBeInTheDocument()
    expect(screen.queryByLabelText('Step 51')).not.toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[49])
    expect(screen.getByRole('button', { name: 'Add step' })).toBeEnabled()
  })

  it('shows the backend field message when saving is rejected', async () => {
    const fetchMock = renderRunbooks('ENGINEER', {
      'PUT /api/runbooks/10': {
        status: 400,
        body: { message: 'Validation failed', errors: { steps: 'A runbook can have at most 50 steps.' } },
      },
    })
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save runbook' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('A runbook can have at most 50 steps.')
    expect(screen.queryByText('Validation failed')).not.toBeInTheDocument()
    expect(callsWith(fetchMock, 'PUT')).toHaveLength(1)
  })

  it('lists the runbooks of every service and shows the one chosen', async () => {
    renderRunbooks('VIEWER', {
      'GET /api/services': { body: [payments, { ...payments, id: 2, name: 'Search' }] },
      'GET /api/services/2/runbooks': { body: [{ id: 20, title: 'Rebuild the search index', steps: ['Pause indexing', 'Rebuild'] }] },
    })

    expect(await screen.findByRole('heading', { name: 'Restart payments service' })).toBeInTheDocument()
    const list = screen.getByRole('navigation', { name: 'Runbooks' })
    await userEvent.click(within(list).getByRole('button', { name: /Rebuild the search index/ }))

    expect(screen.getByRole('heading', { name: 'Rebuild the search index' })).toBeInTheDocument()
    expect(screen.getByText('Pause indexing')).toBeInTheDocument()
    expect(screen.queryByText('Check the service logs')).not.toBeInTheDocument()
    expect(within(list).getByRole('button', { name: /Rebuild the search index/ })).toHaveAttribute('aria-current', 'true')
  })

  it('creates a runbook on the service chosen in the form', async () => {
    const fetchMock = renderRunbooks('ENGINEER', {
      'GET /api/services': { body: [payments, { ...payments, id: 2, name: 'Search' }] },
      'GET /api/services/2/runbooks': { body: [] },
      'POST /api/services/2/runbooks': { status: 201, body: { id: 21, title: 'Clear the cache', steps: ['Flush'] } },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'New runbook' }))
    await userEvent.click(screen.getByLabelText('Service'))
    await userEvent.click(await screen.findByRole('option', { name: 'Search' }))
    await userEvent.type(screen.getByLabelText('Runbook title'), 'Clear the cache')
    await userEvent.type(screen.getByLabelText('Step 1'), 'Flush')
    await userEvent.click(screen.getByRole('button', { name: 'Save runbook' }))

    await waitFor(() => expect(callsWith(fetchMock, 'POST')).toHaveLength(1))
    expect(callsWith(fetchMock, 'POST')[0][0]).toBe('/api/services/2/runbooks')
  })
})
