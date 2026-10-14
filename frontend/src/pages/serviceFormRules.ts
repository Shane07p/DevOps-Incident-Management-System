import { CRITICALITIES, SEVERITIES } from '../api/services'
import type { Criticality, DefaultTargets, Service, ServiceRequest, Severity } from '../api/services'

/** Same limit as the backend: one year in minutes. */
export const MAX_MINUTES = 525_600

/** Form state; inputs hold strings until the request is built. */
export type ServiceFormValues = {
  name: string
  description: string
  ownerId: string
  criticality: string
  targets: Record<Severity, { responseMinutes: string; resolutionMinutes: string }>
}

/** Keys match the backend's error keys, e.g. "targets.SEV1.resolutionMinutes", and the input ids. */
export type FieldErrors = Record<string, string>

export function emptyValues(defaults?: DefaultTargets): ServiceFormValues {
  const targets = {} as ServiceFormValues['targets']
  for (const severity of SEVERITIES) {
    const target = defaults?.[severity]
    targets[severity] = {
      responseMinutes: target ? String(target.responseMinutes) : '',
      resolutionMinutes: target?.resolutionMinutes != null ? String(target.resolutionMinutes) : '',
    }
  }
  return { name: '', description: '', ownerId: '', criticality: '', targets }
}

export function valuesFrom(service: Service): ServiceFormValues {
  const values = emptyValues()
  for (const severity of SEVERITIES) {
    const target = service.targets[severity]
    values.targets[severity] = {
      responseMinutes: String(target.responseMinutes),
      resolutionMinutes: String(target.resolutionMinutes),
    }
  }
  return {
    ...values,
    name: service.name,
    description: service.description ?? '',
    ownerId: String(service.owner.id),
    criticality: service.criticality,
  }
}

/** Browser-side mirror of the server rules, so mistakes show before a round trip. The server re-checks. */
export function validate(values: ServiceFormValues): FieldErrors {
  const errors: FieldErrors = {}
  const name = values.name.trim()
  if (!name) errors.name = 'Name is required'
  else if (name.length > 100) errors.name = 'Name must be at most 100 characters'
  if (values.description.trim().length > 1000) errors.description = 'Description must be at most 1000 characters'
  if (!values.ownerId) errors.ownerId = 'Owner is required'
  if (!(CRITICALITIES as readonly string[]).includes(values.criticality)) errors.criticality = 'Criticality is required'

  for (const severity of SEVERITIES) {
    const field = `targets.${severity}`
    const response = minutes(values.targets[severity].responseMinutes, `${field}.responseMinutes`, 'Response', errors)
    const resolution = minutes(
      values.targets[severity].resolutionMinutes,
      `${field}.resolutionMinutes`,
      'Resolution',
      errors,
    )
    if (response !== null && resolution !== null && resolution < response) {
      errors[`${field}.resolutionMinutes`] = "Resolution target can't be shorter than the response target"
    }
  }
  return errors
}

function minutes(raw: string, field: string, label: string, errors: FieldErrors): number | null {
  const text = raw.trim()
  if (!text) {
    errors[field] = `${label} target is required`
    return null
  }
  if (!/^-?\d+$/.test(text)) {
    errors[field] = 'Enter whole minutes'
    return null
  }
  const value = Number(text)
  if (value < 1) {
    errors[field] = 'Must be at least 1 minute'
    return null
  }
  if (value > MAX_MINUTES) {
    errors[field] = `Must be at most ${MAX_MINUTES} minutes (one year)`
    return null
  }
  return value
}

/** Only call after `validate` returned no errors. */
export function toRequest(values: ServiceFormValues): ServiceRequest {
  const targets = {} as ServiceRequest['targets']
  for (const severity of SEVERITIES) {
    targets[severity] = {
      responseMinutes: Number(values.targets[severity].responseMinutes),
      resolutionMinutes: Number(values.targets[severity].resolutionMinutes),
    }
  }
  const description = values.description.trim()
  return {
    name: values.name.trim(),
    description: description || null,
    ownerId: Number(values.ownerId),
    criticality: values.criticality as Criticality,
    targets,
  }
}

/** 90 → "1 h 30 min", 1440 → "1 d". */
export function formatMinutes(total: number): string {
  const days = Math.floor(total / 1440)
  const hours = Math.floor((total % 1440) / 60)
  const mins = total % 60
  const parts = [days && `${days} d`, hours && `${hours} h`, mins && `${mins} min`].filter(Boolean)
  return parts.join(' ') || '0 min'
}
