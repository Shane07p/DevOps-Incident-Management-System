import { z } from 'zod'
import { apiFetch } from './client'
import { userSchema, type Role } from './schemas'

export interface NewUser {
  username: string
  displayName: string
  password: string
  role: Role
}

/** Admins only. Passwords never come back from the server. */
export function listUsers() {
  return apiFetch('/users', z.array(userSchema))
}

export function createUser(user: NewUser) {
  return apiFetch('/users', userSchema, { method: 'POST', body: user })
}

/** The server logs the user out everywhere, so their next request uses the new role. */
export function changeRole(id: number, role: Role) {
  return apiFetch(`/users/${id}/role`, userSchema, { method: 'PUT', body: { role } })
}
