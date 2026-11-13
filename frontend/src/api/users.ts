import { z } from 'zod'
import { apiFetch } from './client'
import { memberSchema, userSchema, type Role } from './schemas'

export interface NewUser {
  email: string
  displayName: string
  password: string
  role: Role
}

/** Admins only, and only the Admin's own org. Passwords never come back from the server. */
export function listUsers() {
  return apiFetch('/users', z.array(memberSchema))
}

export function createUser(user: NewUser) {
  return apiFetch('/users', userSchema, { method: 'POST', body: user })
}

/** The server logs the user out everywhere, so their next request uses the new role. */
export function changeRole(id: number, role: Role) {
  return apiFetch(`/users/${id}/role`, userSchema, { method: 'PUT', body: { role } })
}

/** Disabling logs the member out everywhere and refuses their sign-in until they are enabled again. */
export function setEnabled(id: number, enabled: boolean) {
  return apiFetch(`/users/${id}/enabled`, memberSchema, { method: 'PUT', body: { enabled } })
}
