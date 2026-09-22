import { render, screen } from '@testing-library/react'
import App from './App'

test('renders the application title', () => {
  render(<App />)
  expect(screen.getByText('Incident Management System')).toBeInTheDocument()
})
