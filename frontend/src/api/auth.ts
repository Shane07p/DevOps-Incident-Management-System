import { apiFetch } from './client'
import { loginResponseSchema, meResponseSchema, noContentSchema } from './schemas'

export function login(email: string, password: string) {
  return apiFetch('/auth/login', loginResponseSchema, { method: 'POST', body: { email, password } })
}

export interface SignupValues {
  email: string
  password: string
  displayName: string
  orgName: string
}

/** Creates the account and a new org with the caller as its Admin, and signs them in. */
export function signup(values: SignupValues) {
  return apiFetch('/auth/signup', loginResponseSchema, { method: 'POST', body: values })
}

export function fetchMe() {
  return apiFetch('/auth/me', meResponseSchema)
}

export function logout() {
  return apiFetch('/auth/logout', noContentSchema, { method: 'POST' })
}
