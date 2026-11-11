import { z } from 'zod'
import { apiFetch } from './client'
import { noContentSchema } from './schemas'

const timestamp = z.iso.datetime({ offset: true })

export const personalAccessTokenSchema = z.object({
  id: z.number(),
  name: z.string(),
  createdAt: timestamp,
  expiresAt: timestamp,
  // Null until the token has been used by an AI assistant or script.
  lastUsedAt: timestamp.nullable(),
  // Null while active; the moment it was revoked after that.
  revokedAt: timestamp.nullable(),
})

export type PersonalAccessToken = z.infer<typeof personalAccessTokenSchema>

/** The only response that carries the plaintext token: the server keeps only its SHA-256 hash. */
export const createPersonalAccessTokenResponseSchema = z.object({
  id: z.number(),
  name: z.string(),
  token: z.string(),
  createdAt: timestamp,
  expiresAt: timestamp,
})

export type CreatePersonalAccessTokenResponse = z.infer<typeof createPersonalAccessTokenResponseSchema>

/** Lists personal access tokens created by the current user. Plaintext tokens and hashes are never returned. */
export function listPersonalAccessTokens() {
  return apiFetch('/me/tokens', z.array(personalAccessTokenSchema))
}

/** Creates a personal access token with a name; returns the plaintext token exactly once. */
export function createPersonalAccessToken(name: string) {
  return apiFetch('/me/tokens', createPersonalAccessTokenResponseSchema, {
    method: 'POST',
    body: { name },
  })
}

/** Revokes a personal access token by ID. Idempotent on the backend. */
export function revokePersonalAccessToken(id: number) {
  return apiFetch(`/me/tokens/${id}`, noContentSchema, { method: 'DELETE' })
}
