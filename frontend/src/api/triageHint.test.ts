import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { fetchTriageHint } from './triageHint'

afterEach(() => {
  vi.unstubAllGlobals()
})

const hint = { aiGenerated: true, severity: 'SEV2', service: null, reason: 'Checkout errors usually start as SEV2', similar: [] }

describe('fetchTriageHint', () => {
  it('asks nothing for a title under three characters', async () => {
    const fetchMock = mockFetch({})

    expect(await fetchTriageHint('  ab  ')).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends the trimmed title, and an empty description when there is none', async () => {
    const fetchMock = mockFetch({ 'POST /api/incidents/triage-hint': { body: hint } })

    await fetchTriageHint('  Checkout down ')

    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(String(init?.body))).toEqual({ title: 'Checkout down', description: '' })
  })

  it('gives nothing back, and throws nothing, when the AI is unavailable', async () => {
    mockFetch({ 'POST /api/incidents/triage-hint': { status: 503, body: { status: 503, message: 'AI is unavailable' } } })

    await expect(fetchTriageHint('Checkout down', '502s')).resolves.toBeNull()
  })
})
