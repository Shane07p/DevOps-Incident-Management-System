import { describe, expect, it } from 'vitest'
import { readFilters } from './filters'

describe('readFilters', () => {
  it('reads the filters a link carries', () => {
    expect(readFilters(new URLSearchParams('scope=mine&q=pool&service=Payments&severity=SEV1&status=OPEN'))).toEqual({
      scope: 'mine',
      q: 'pool',
      service: 'Payments',
      severity: 'SEV1',
      status: 'OPEN',
    })
  })

  it('falls back to everything for a severity, status or scope it does not know', () => {
    expect(readFilters(new URLSearchParams('scope=someone&severity=SEV9&status=FINISHED'))).toEqual({
      scope: 'all',
      q: '',
      service: '',
      severity: '',
      status: '',
    })
  })
})
