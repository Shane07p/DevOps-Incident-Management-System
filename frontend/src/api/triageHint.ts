import { z } from 'zod'
import { apiFetch } from './client'

// ---------------------------------------------------------------------------
// Schema — matches POST /api/incidents/triage-hint response contract
// ---------------------------------------------------------------------------

export const triageHintServiceSchema = z.object({
  id: z.number().nullable().optional(),
  name: z.string().nullable().optional(),
})

export const triageHintSchema = z.object({
  severity: z.string(),
  service: triageHintServiceSchema.nullable().optional(),
  reason: z.string(),
})

export type TriageHint = z.infer<typeof triageHintSchema>

// ---------------------------------------------------------------------------
// API function
// ---------------------------------------------------------------------------

/**
 * POST /api/incidents/triage-hint
 *
 * Returns a triage hint when the AI service is available, or null when:
 *   - title is too short (< 3 chars)
 *   - server returns 503 (AI unavailable)
 *   - any other error
 *
 * Never throws — the caller (TriageHint component) silently hides on failure.
 */
export async function fetchTriageHint(
  title: string,
  description: string,
): Promise<TriageHint | null> {
  const trimmedTitle = title.trim()
  const trimmedDesc = description.trim()
  if (trimmedTitle.length < 3) return null

  try {
    return await apiFetch('/incidents/triage-hint', triageHintSchema, {
      method: 'POST',
      body: { title: trimmedTitle, description: trimmedDesc || 'No description provided.' },
    })
  } catch {
    // AI unavailable or error — return null, never throw (never block declaration)
    return null
  }
}
