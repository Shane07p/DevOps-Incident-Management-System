import type { FieldPath, FieldValues, UseFormReturn } from 'react-hook-form'
import type { ApiRequestError } from '@/api/client'

/**
 * Shows a rejected request on its form: each field error goes under its input; one the form has no input
 * for, or a bare message, goes above the buttons. `fields` are the form's input names.
 */
export function showRequestErrors<T extends FieldValues>(form: UseFormReturn<T>, error: ApiRequestError, fields: Iterable<string>) {
  const known = new Set(fields)
  const unplaced: string[] = []
  for (const [field, message] of Object.entries(error.errors)) {
    if (known.has(field)) form.setError(field as FieldPath<T>, { message })
    else unplaced.push(message)
  }
  if (unplaced.length > 0) form.setError('root', { message: unplaced.join(' ') })
  else if (Object.keys(error.errors).length === 0) form.setError('root', { message: error.message })
}
