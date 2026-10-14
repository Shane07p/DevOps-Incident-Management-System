import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiRequestError } from '../api/client'
import {
  CRITICALITIES,
  SEVERITIES,
  createService,
  getDefaultTargets,
  getService,
  listUsers,
  updateService,
} from '../api/services'
import type { ServiceRequest, Severity } from '../api/services'
import { emptyValues, toRequest, validate, valuesFrom } from './serviceFormRules'
import type { FieldErrors, ServiceFormValues } from './serviceFormRules'

/** Create (/services/new) or edit (/services/:id/edit) a service. Admin only. */
export default function ServiceForm() {
  const { id } = useParams()
  const serviceId = id === undefined ? null : Number(id)

  const defaults = useQuery({
    queryKey: ['services', 'default-targets'],
    queryFn: getDefaultTargets,
    enabled: serviceId === null,
  })
  const existing = useQuery({
    queryKey: ['services', serviceId],
    queryFn: () => getService(serviceId!),
    enabled: serviceId !== null,
  })
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers })

  const loading = serviceId === null ? defaults : existing
  if (loading.isPending) return <p>Loading…</p>
  if (loading.isError) return <p role="alert">Could not load the form: {loading.error.message}</p>

  const initial = serviceId === null ? emptyValues(defaults.data) : valuesFrom(existing.data!)
  return (
    <Form
      key={serviceId ?? 'new'}
      serviceId={serviceId}
      initial={initial}
      owners={users.data ?? []}
      ownersError={users.isError}
    />
  )
}

type FormProps = {
  serviceId: number | null
  initial: ServiceFormValues
  owners: { id: number; displayName: string }[]
  ownersError: boolean
}

function Form({ serviceId, initial, owners, ownersError }: FormProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [values, setValues] = useState(initial)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: (body: ServiceRequest) => (serviceId === null ? createService(body) : updateService(serviceId, body)),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['services'] })
      navigate('/services')
    },
    onError: (error) => {
      if (error instanceof ApiRequestError && Object.keys(error.errors).length > 0) {
        setErrors(error.errors)
      } else if (error instanceof ApiRequestError && error.status === 409) {
        setErrors({ name: error.message })
      } else {
        setFormError(error.message)
      }
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const found = validate(values)
    setErrors(found)
    if (Object.keys(found).length === 0) save.mutate(toRequest(values))
  }

  function setTarget(severity: Severity, key: 'responseMinutes' | 'resolutionMinutes', value: string) {
    setValues((v) => ({ ...v, targets: { ...v.targets, [severity]: { ...v.targets[severity], [key]: value } } }))
  }

  // An owner who is no longer in the list still shows, so editing doesn't silently change it.
  const ownerChoices =
    values.ownerId && !owners.some((o) => String(o.id) === values.ownerId)
      ? [...owners, { id: Number(values.ownerId), displayName: `User ${values.ownerId}` }]
      : owners

  return (
    <form onSubmit={submit} noValidate className="max-w-3xl space-y-4">
      <h2 className="text-xl font-semibold">{serviceId === null ? 'Register a service' : 'Edit service'}</h2>
      {formError && (
        <p role="alert" className="text-red-600">
          {formError}
        </p>
      )}

      <Field id="name" label="Name" error={errors.name}>
        <input
          id="name"
          name="name"
          value={values.name}
          onChange={(e) => setValues({ ...values, name: e.target.value })}
          {...invalid('name', errors)}
        />
      </Field>

      <Field id="description" label="Description (optional)" error={errors.description}>
        <textarea
          id="description"
          name="description"
          rows={2}
          value={values.description}
          onChange={(e) => setValues({ ...values, description: e.target.value })}
          {...invalid('description', errors)}
        />
      </Field>

      <Field id="ownerId" label="Owner" error={errors.ownerId ?? (ownersError ? 'Could not load users' : undefined)}>
        <select
          id="ownerId"
          name="ownerId"
          value={values.ownerId}
          onChange={(e) => setValues({ ...values, ownerId: e.target.value })}
          {...invalid('ownerId', errors)}
        >
          <option value="">Choose an owner</option>
          {ownerChoices.map((owner) => (
            <option key={owner.id} value={owner.id}>
              {owner.displayName}
            </option>
          ))}
        </select>
      </Field>

      <Field id="criticality" label="Criticality" error={errors.criticality}>
        <select
          id="criticality"
          name="criticality"
          value={values.criticality}
          onChange={(e) => setValues({ ...values, criticality: e.target.value })}
          {...invalid('criticality', errors)}
        >
          <option value="">Choose criticality</option>
          {CRITICALITIES.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </Field>

      <fieldset>
        <legend className="font-medium">Targets per severity, in minutes</legend>
        {errors.targets && <p className="text-sm text-red-600">{errors.targets}</p>}
        <table className="mt-2 text-sm">
          <thead>
            <tr>
              <th className="pr-4 text-left">Severity</th>
              <th className="pr-4 text-left">Respond within</th>
              <th className="text-left">Resolve within</th>
            </tr>
          </thead>
          <tbody>
            {SEVERITIES.map((severity) => (
              <tr key={severity}>
                <th scope="row" className="pr-4 text-left align-top">
                  {severity}
                  {errors[`targets.${severity}`] && (
                    <p className="text-red-600">{errors[`targets.${severity}`]}</p>
                  )}
                </th>
                {(['responseMinutes', 'resolutionMinutes'] as const).map((key) => {
                  const field = `targets.${severity}.${key}`
                  return (
                    <td key={key} className="pr-4 align-top">
                      <label htmlFor={field} className="sr-only">
                        {severity} {key === 'responseMinutes' ? 'response' : 'resolution'} minutes
                      </label>
                      <input
                        id={field}
                        name={field}
                        type="number"
                        min={1}
                        step={1}
                        inputMode="numeric"
                        className="w-28 rounded border px-2 py-1"
                        value={values.targets[severity][key]}
                        onChange={(e) => setTarget(severity, key, e.target.value)}
                        {...invalid(field, errors)}
                      />
                      {errors[field] && (
                        <p id={`${field}-error`} className="text-red-600">
                          {errors[field]}
                        </p>
                      )}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </fieldset>

      <div className="flex gap-2">
        <button type="submit" disabled={save.isPending} className="rounded bg-blue-600 px-3 py-1.5 text-white">
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
        <button type="button" onClick={() => navigate('/services')} className="rounded border px-3 py-1.5">
          Cancel
        </button>
      </div>
    </form>
  )
}

function invalid(field: string, errors: FieldErrors) {
  return errors[field] ? { 'aria-invalid': true, 'aria-describedby': `${field}-error` } : {}
}

function Field({ id, label, error, children }: { id: string; label: string; error?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 [&_input]:rounded [&_input]:border [&_input]:px-2 [&_input]:py-1 [&_select]:rounded [&_select]:border [&_select]:px-2 [&_select]:py-1 [&_textarea]:rounded [&_textarea]:border [&_textarea]:px-2 [&_textarea]:py-1">
      <label htmlFor={id} className="font-medium">
        {label}
      </label>
      {children}
      {error && (
        <p id={`${id}-error`} className="text-sm text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}
