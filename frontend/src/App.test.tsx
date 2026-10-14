import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import App from './App'
import { mockFetch } from './test/fetchMock'

afterEach(() => vi.unstubAllGlobals())

test('renders the application title and opens on the service list', async () => {
  mockFetch({ 'GET /api/services': { body: [] } })
  render(
    <QueryClientProvider client={new QueryClient()}>
      <App />
    </QueryClientProvider>,
  )
  expect(screen.getByText('Incident Management System')).toBeInTheDocument()
  expect(await screen.findByText('No services registered yet.')).toBeInTheDocument()
})
