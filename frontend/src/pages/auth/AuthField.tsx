import type { InputHTMLAttributes } from 'react'
import type { FieldPath, FieldValues, UseFormReturn } from 'react-hook-form'

/**
 * One labelled input in the preview's style (#267). Its id is the field name. An error shows only once the
 * field has been left (the form validates on blur), and it clears as soon as the field is focused or typed
 * in, so a field is never marked wrong while someone is still fixing it.
 */
export function AuthField<T extends FieldValues>({
  form,
  name,
  label,
  ...input
}: { form: UseFormReturn<T>; name: FieldPath<T>; label: string } & Omit<InputHTMLAttributes<HTMLInputElement>, 'name' | 'form'>) {
  const message = form.formState.errors[name]?.message as string | undefined
  const registered = form.register(name)
  const clear = () => {
    if (message) form.clearErrors(name)
  }
  return (
    <label className="field-label">
      {/* The input is named by this text alone; the error below is its description, not part of its name. */}
      <span id={`${name}-label`}>{label}</span>
      <input
        id={name}
        aria-labelledby={`${name}-label`}
        required
        aria-invalid={message ? true : undefined}
        aria-describedby={message ? `${name}-error` : undefined}
        {...input}
        {...registered}
        onFocus={clear}
        onChange={(event) => {
          clear()
          return registered.onChange(event)
        }}
      />
      {message && (
        <span className="auth-error" id={`${name}-error`}>
          {message}
        </span>
      )}
    </label>
  )
}
