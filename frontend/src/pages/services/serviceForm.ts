import { z } from 'zod'
import { criticalitySchema, type Criticality, type Severity } from '@/api/schemas'
import { DEFAULT_RESPONSE_MINUTES, SEVERITIES, type Service, type ServiceRequest } from '@/api/services'

const isWholeMinutes = (value: string) => /^\d+$/.test(value) && Number(value) >= 1

// Inputs hold text; numbers are made on submit, so an empty box stays empty instead of becoming 0.
const minutesSchema = z
  .string()
  .trim()
  .min(1, { error: 'Enter minutes' })
  .refine(isWholeMinutes, { error: 'Use whole minutes, at least 1' })

// The server checks the same rule and names the same field (TC-FR-14.1i, TC-FR-14.1d).
const targetSchema = z
  .object({ responseMinutes: minutesSchema, resolutionMinutes: minutesSchema })
  .refine(
    ({ responseMinutes, resolutionMinutes }) =>
      !isWholeMinutes(responseMinutes) ||
      !isWholeMinutes(resolutionMinutes) ||
      Number(resolutionMinutes) >= Number(responseMinutes),
    { error: "Resolution target can't be shorter than the response target", path: ['resolutionMinutes'] },
  )

// The server checks every rule again; these only save a round trip.
export const serviceFormSchema = z.object({
  name: z.string().trim().min(1, { error: 'Enter a name' }).max(100, { error: 'Use at most 100 characters' }),
  description: z.string().trim().max(1000, { error: 'Use at most 1000 characters' }),
  ownerId: z.string().min(1, { error: 'Choose an owner' }),
  // Starts empty so the Admin picks one. A length check, not `!== ''`, so TypeScript keeps '' in the
  // form's type and the resolver's input and output types stay the same.
  criticality: z.union([criticalitySchema, z.literal('')]).refine((value) => value.length > 0, { error: 'Choose a criticality' }),
  targets: z.record(z.enum(SEVERITIES), targetSchema),
})

export type ServiceFormValues = z.infer<typeof serviceFormSchema>

function bySeverity<T>(make: (severity: Severity) => T) {
  return Object.fromEntries(SEVERITIES.map((severity) => [severity, make(severity)])) as Record<Severity, T>
}

/** A new service: response targets start at the defaults, resolution targets are left to the Admin. */
export function emptyValues(): ServiceFormValues {
  return {
    name: '',
    description: '',
    ownerId: '',
    criticality: '',
    targets: bySeverity((severity) => ({ responseMinutes: String(DEFAULT_RESPONSE_MINUTES[severity]), resolutionMinutes: '' })),
  }
}

export function valuesFrom(service: Service): ServiceFormValues {
  return {
    name: service.name,
    description: service.description ?? '',
    ownerId: String(service.owner.id),
    criticality: service.criticality,
    targets: bySeverity((severity) => ({
      responseMinutes: String(service.targets[severity].responseMinutes),
      resolutionMinutes: String(service.targets[severity].resolutionMinutes),
    })),
  }
}

/** Only called with values the schema accepted, so the casts hold. */
export function toRequest(values: ServiceFormValues): ServiceRequest {
  return {
    name: values.name,
    description: values.description,
    ownerId: Number(values.ownerId),
    criticality: values.criticality as Criticality,
    targets: bySeverity((severity) => ({
      responseMinutes: Number(values.targets[severity].responseMinutes),
      resolutionMinutes: Number(values.targets[severity].resolutionMinutes),
    })),
  }
}

/** Field names the server may put in `errors`; each matches an input id. */
export const FIELD_NAMES: ReadonlySet<string> = new Set([
  'name',
  'description',
  'ownerId',
  'criticality',
  ...SEVERITIES.flatMap((severity) => [`targets.${severity}.responseMinutes`, `targets.${severity}.resolutionMinutes`]),
])
