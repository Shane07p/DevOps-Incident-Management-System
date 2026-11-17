import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '@/test/fetchMock'
import { fetchInsights, insightsResponseSchema, type InsightsResponse } from './insights'

const validResponse: InsightsResponse = {
  from: '2026-10-15T00:00:00Z',
  to: '2026-11-14T00:00:00Z',
  incidents: {
    total: 10,
    open: 2,
    resolved: 8,
  },
  mtta: {
    mean: 15.0,
    median: 10.0,
    p90: 25.0,
  },
  mttr: {
    mean: 120.0,
    median: 90.0,
    p90: 240.0,
  },
  bySeverity: [
    { severity: 'SEV1', count: 1 },
    { severity: 'SEV2', count: 3 },
    { severity: 'SEV3', count: 4 },
    { severity: 'SEV4', count: 2 },
  ],
  byService: [
    { serviceId: 101, name: 'Billing', count: 6 },
    { serviceId: 102, name: 'Auth', count: 4 },
  ],
  slaBreachRate: {
    response: 0.1,
    resolution: 0.2,
  },
  followUps: {
    total: 5,
    done: 4,
    overdue: 1,
    completionRate: 0.8,
  },
  outOfHoursPages: 3,
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchInsights', () => {
  it('requests /api/insights?days=30 by default and validates the response', async () => {
    const fetchMock = mockFetch({
      'GET /api/insights?days=30': { body: validResponse },
    })

    const result = await fetchInsights()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=30',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(result.incidents.total).toBe(10)
    expect(result.byService[0].serviceId).toBe(101)
  })

  it('requests /api/insights with the specified days', async () => {
    const fetchMock = mockFetch({
      'GET /api/insights?days=7': { body: validResponse },
    })

    const result = await fetchInsights(7)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/insights?days=7',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(result.outOfHoursPages).toBe(3)
  })

  it('validates response with null metrics without converting to zero', () => {
    const withNulls = {
      ...validResponse,
      mtta: { mean: null, median: null, p90: null },
      mttr: { mean: null, median: null, p90: null },
      slaBreachRate: { response: null, resolution: null },
      followUps: { total: 0, done: 0, overdue: 0, completionRate: null },
    }

    const parsed = insightsResponseSchema.parse(withNulls)

    expect(parsed.mtta.mean).toBeNull()
    expect(parsed.mttr.median).toBeNull()
    expect(parsed.slaBreachRate.response).toBeNull()
    expect(parsed.followUps.completionRate).toBeNull()
  })

  it('rejects a response that violates the contract', () => {
    const invalid = {
      ...validResponse,
      bySeverity: [{ severity: 'INVALID_SEV', count: 1 }],
    }

    expect(() => insightsResponseSchema.parse(invalid)).toThrow()
  })
})
