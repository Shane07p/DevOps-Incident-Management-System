import { z } from 'zod'
import { apiFetch } from './client'
import { noContentSchema } from './schemas'

const timestamp = z.iso.datetime({ offset: true })

export const toolSchema = z.object({
  id: z.number(),
  name: z.string(),
  createdAt: timestamp,
  // Null while the token works; the moment it was revoked after that.
  revokedAt: timestamp.nullable(),
})

export type Tool = z.infer<typeof toolSchema>

/** The only response that carries the token: the server keeps just its hash. */
export const registeredToolSchema = z.object({
  id: z.number(),
  name: z.string(),
  token: z.string(),
  createdAt: timestamp,
})

export type RegisteredTool = z.infer<typeof registeredToolSchema>

/** Admins only. Newest first; a token never comes back after registration. */
export function listTools() {
  return apiFetch('/tools', z.array(toolSchema))
}

export function registerTool(name: string) {
  return apiFetch('/tools', registeredToolSchema, { method: 'POST', body: { name } })
}

/** Final: alerts sent with the token get 401 from then on. */
export function revokeTool(id: number) {
  return apiFetch(`/tools/${id}`, noContentSchema, { method: 'DELETE' })
}
