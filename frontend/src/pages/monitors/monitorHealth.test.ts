import { describe, expect, it } from 'vitest'
import type { Monitor } from '@/api/monitors'
import { monitorHealth } from './monitorHealth'

const up: Monitor = {
  id: 1, name: 'Checkout health', url: 'https://shop.example/health', expectedStatus: 200, intervalSeconds: 60,
  serviceId: 1, serviceName: 'Checkout', failureThreshold: 3, enabled: true, consecutiveFailures: 0,
  outageStartedAt: null, activeAlertId: null, lastCheckedAt: '2026-11-19T09:59:00Z', nextCheckAt: '2026-11-19T10:00:00Z',
  createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
}

describe('monitorHealth', () => {
  it('reads paused, down, failing, not checked yet and up from the monitor alone', () => {
    expect(monitorHealth({ ...up, enabled: false, outageStartedAt: '2026-11-19T09:00:00Z' })).toEqual({ tone: 'neutral', label: 'Paused' })
    expect(monitorHealth({ ...up, outageStartedAt: '2026-11-19T09:00:00Z' })).toMatchObject({ tone: 'fail', label: expect.stringMatching(/^Down since /) })
    expect(monitorHealth({ ...up, consecutiveFailures: 2 })).toEqual({ tone: 'warn', label: 'Failing · 2 of 3 checks' })
    expect(monitorHealth({ ...up, lastCheckedAt: null })).toEqual({ tone: 'neutral', label: 'Not checked yet' })
    expect(monitorHealth(up)).toEqual({ tone: 'ok', label: 'Up' })
  })
})
