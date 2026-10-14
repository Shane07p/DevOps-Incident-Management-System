import { emptyValues, formatMinutes, toRequest, validate } from './serviceFormRules'
import type { ServiceFormValues } from './serviceFormRules'

function filled(): ServiceFormValues {
  const values = emptyValues()
  for (const severity of ['SEV1', 'SEV2', 'SEV3', 'SEV4'] as const) {
    values.targets[severity] = { responseMinutes: '15', resolutionMinutes: '240' }
  }
  return { ...values, name: ' Payments ', description: '', ownerId: '1', criticality: 'HIGH' }
}

test('a complete form has no errors and builds the request', () => {
  const values = filled()
  expect(validate(values)).toEqual({})
  expect(toRequest(values)).toEqual({
    name: 'Payments',
    description: null,
    ownerId: 1,
    criticality: 'HIGH',
    targets: {
      SEV1: { responseMinutes: 15, resolutionMinutes: 240 },
      SEV2: { responseMinutes: 15, resolutionMinutes: 240 },
      SEV3: { responseMinutes: 15, resolutionMinutes: 240 },
      SEV4: { responseMinutes: 15, resolutionMinutes: 240 },
    },
  })
})

test('missing fields are named', () => {
  const errors = validate(emptyValues())
  expect(errors.name).toBe('Name is required')
  expect(errors.ownerId).toBe('Owner is required')
  expect(errors.criticality).toBe('Criticality is required')
  expect(errors['targets.SEV1.responseMinutes']).toBe('Response target is required')
  expect(errors['targets.SEV4.resolutionMinutes']).toBe('Resolution target is required')
})

test('defaults prefill response targets only', () => {
  const values = emptyValues({
    SEV1: { responseMinutes: 15, resolutionMinutes: null },
    SEV2: { responseMinutes: 60, resolutionMinutes: null },
    SEV3: { responseMinutes: 240, resolutionMinutes: null },
    SEV4: { responseMinutes: 1440, resolutionMinutes: null },
  })
  expect(values.targets.SEV1).toEqual({ responseMinutes: '15', resolutionMinutes: '' })
  expect(values.targets.SEV4).toEqual({ responseMinutes: '1440', resolutionMinutes: '' })
})

test('resolution may equal but not undercut response', () => {
  const values = filled()
  values.targets.SEV2 = { responseMinutes: '60', resolutionMinutes: '60' }
  expect(validate(values)).toEqual({})
  values.targets.SEV2 = { responseMinutes: '60', resolutionMinutes: '59' }
  expect(validate(values)['targets.SEV2.resolutionMinutes']).toBe(
    "Resolution target can't be shorter than the response target",
  )
})

test('targets must be whole minutes between 1 and one year', () => {
  const values = filled()
  values.targets.SEV1.responseMinutes = '0'
  values.targets.SEV2.responseMinutes = '1.5'
  values.targets.SEV3.resolutionMinutes = '525601'
  const errors = validate(values)
  expect(errors['targets.SEV1.responseMinutes']).toBe('Must be at least 1 minute')
  expect(errors['targets.SEV2.responseMinutes']).toBe('Enter whole minutes')
  expect(errors['targets.SEV3.resolutionMinutes']).toBe('Must be at most 525600 minutes (one year)')
})

test('a blank name is a missing name', () => {
  expect(validate({ ...filled(), name: '   ' }).name).toBe('Name is required')
})

test('minutes read as days, hours and minutes', () => {
  expect(formatMinutes(15)).toBe('15 min')
  expect(formatMinutes(90)).toBe('1 h 30 min')
  expect(formatMinutes(1440)).toBe('1 d')
  expect(formatMinutes(10080)).toBe('7 d')
})
