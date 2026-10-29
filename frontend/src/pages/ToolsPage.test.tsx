import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { ToolsPage } from './ToolsPage'

const TOKEN = 'tt_3f9a0c7d2b1e4f6a8c5d9e0b1a2c3d4e5f60718293a4b5c6d7e8f9012345678'
const grafana = { id: 1, name: 'Grafana', createdAt: '2026-10-20T04:30:00Z', revokedAt: null }
const oldPrometheus = { id: 2, name: 'Old Prometheus', createdAt: '2026-10-18T04:30:00Z', revokedAt: '2026-10-19T04:30:00Z' }

function renderTools() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToolsPage />
    </QueryClientProvider>,
  )
}

const callsWith = (fetchMock: ReturnType<typeof mockFetch>, method: string) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === method)

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ToolsPage', () => {
  it('lists tools, with Revoke only for tokens that still work', async () => {
    mockFetch({ 'GET /api/tools': { body: [grafana, oldPrometheus] } })
    renderTools()

    expect(await screen.findByText('Grafana')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revoke Grafana' })).toBeInTheDocument()
    expect(screen.getByText(/^Revoked/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke Old Prometheus' })).not.toBeInTheDocument()
  })

  // TC-US-02a: the token is shown once, and closing the dialog drops it for good.
  it('shows the new token once, and drops it when the dialog closes', async () => {
    const user = userEvent.setup()
    mockFetch({
      'GET /api/tools': { body: [] },
      'POST /api/tools': { status: 201, body: { id: 3, name: 'Grafana', token: TOKEN, createdAt: '2026-10-29T04:30:00Z' } },
    })
    renderTools()

    await user.click(await screen.findByRole('button', { name: 'Register tool' }))
    const form = await screen.findByRole('dialog', { name: 'Register a tool' })
    await user.type(within(form).getByLabelText('Name'), 'Grafana')
    await user.click(within(form).getByRole('button', { name: 'Register tool' }))

    const tokenDialog = await screen.findByRole('dialog', { name: 'Copy the token for Grafana' })
    expect(within(tokenDialog).getByLabelText('Token')).toHaveValue(TOKEN)
    expect(within(tokenDialog).getByRole('alert')).toHaveTextContent("it can't be shown again")
    await user.click(within(tokenDialog).getByRole('button', { name: 'Copy' }))
    expect(await navigator.clipboard.readText()).toBe(TOKEN)
    expect(within(tokenDialog).getByRole('button', { name: 'Copied' })).toBeInTheDocument()

    await user.click(within(tokenDialog).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByDisplayValue(TOKEN)).not.toBeInTheDocument()

    // Opening the dialog again starts a new registration; the old token is not there.
    await user.click(screen.getByRole('button', { name: 'Register tool' }))
    const again = await screen.findByRole('dialog', { name: 'Register a tool' })
    expect(within(again).getByLabelText('Name')).toHaveValue('')
    expect(screen.queryByDisplayValue(TOKEN)).not.toBeInTheDocument()
  })

  it('shows a duplicate name on the field', async () => {
    const user = userEvent.setup()
    mockFetch({
      'GET /api/tools': { body: [grafana] },
      'POST /api/tools': { status: 409, body: { status: 409, message: "Tool with name 'grafana' already exists", errors: {} } },
    })
    renderTools()

    await user.click(await screen.findByRole('button', { name: 'Register tool' }))
    const form = await screen.findByRole('dialog', { name: 'Register a tool' })
    await user.type(within(form).getByLabelText('Name'), 'grafana')
    await user.click(within(form).getByRole('button', { name: 'Register tool' }))

    expect(await within(form).findByText("Tool with name 'grafana' already exists")).toBeInTheDocument()
  })

  // TC-US-02b: revoking needs a confirmation; cancelling sends nothing.
  it('revokes a token only after confirmation', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch({
      'GET /api/tools': { body: [grafana] },
      'DELETE /api/tools/1': { status: 204, body: null },
    })
    renderTools()

    await user.click(await screen.findByRole('button', { name: 'Revoke Grafana' }))
    const confirm = await screen.findByRole('dialog', { name: "Revoke Grafana's token?" })
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    expect(callsWith(fetchMock, 'DELETE')).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Revoke Grafana' }))
    const again = await screen.findByRole('dialog', { name: "Revoke Grafana's token?" })
    await user.click(within(again).getByRole('button', { name: 'Revoke token' }))
    await waitFor(() => expect(callsWith(fetchMock, 'DELETE')).toHaveLength(1))
  })
})
