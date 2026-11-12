import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, type FieldValues, type UseFormReturn } from 'react-hook-form'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { addComment, correctTimelineEntry, type TimelineEntry } from '@/api/incidents'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Textarea } from '@/components/ui/textarea'
import { showRequestErrors } from '@/lib/forms'
import { formatIst } from '@/lib/time'

// The server checks the same limit (CommentRequest, CorrectionRequest); this only saves a round trip.
const MAX_LENGTH = 5000

const commentSchema = z.object({
  comment: z.string().trim().min(1, { error: 'Write a comment' }).max(MAX_LENGTH, { error: 'At most 5,000 characters' }),
})

const correctionSchema = z.object({
  correction: z.string().trim().min(1, { error: 'Say what was wrong and what is right' }).max(MAX_LENGTH, { error: 'At most 5,000 characters' }),
})

// "INCIDENT_CREATED" → "Incident created"; unknown types from later sprints still read sensibly.
const eventLabel = (eventType: string) => {
  const words = eventType.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const anchor = (entry: TimelineEntry) => `entry-${entry.id}`

/** "Comment by Dev Engineer, 20 Oct, 14:05 IST": enough to tell which entry a link points to. */
const summarize = (entry: TimelineEntry) => `${eventLabel(entry.eventType)} by ${entry.actor ?? 'System'}, ${formatIst(entry.recordedAt)}`

function showFailure<T extends FieldValues>(form: UseFormReturn<T>, error: unknown, field: string) {
  if (error instanceof ApiRequestError) showRequestErrors(form, error, [field])
  else form.setError('root', { message: 'Settle could not be reached. Try again in a moment.' })
}

function CommentForm({ incidentId }: { incidentId: number }) {
  const queryClient = useQueryClient()
  const form = useForm<z.infer<typeof commentSchema>>({ resolver: zodResolver(commentSchema), defaultValues: { comment: '' } })

  const onSubmit = form.handleSubmit(async ({ comment }) => {
    try {
      await addComment(incidentId, comment)
      form.reset({ comment: '' })
      await queryClient.invalidateQueries({ queryKey: ['incident', incidentId] })
    } catch (error) {
      showFailure(form, error, 'comment')
    }
  })

  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3 rounded-lg border bg-card p-4">
        <FormField
          control={form.control}
          name="comment"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Add a comment</FormLabel>
              <FormControl>
                <Textarea rows={3} placeholder="What you checked, what you found, or what happens next" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        {form.formState.errors.root && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {form.formState.errors.root.message}
          </p>
        )}
        <Button type="submit" disabled={form.formState.isSubmitting} className="w-fit">
          {form.formState.isSubmitting ? 'Posting…' : 'Post comment'}
        </Button>
      </form>
    </Form>
  )
}

function CorrectionForm({ incidentId, entry, onDone }: { incidentId: number; entry: TimelineEntry; onDone: () => void }) {
  const queryClient = useQueryClient()
  const form = useForm<z.infer<typeof correctionSchema>>({ resolver: zodResolver(correctionSchema), defaultValues: { correction: '' } })

  const onSubmit = form.handleSubmit(async ({ correction }) => {
    try {
      await correctTimelineEntry(incidentId, entry.id, correction)
      await queryClient.invalidateQueries({ queryKey: ['incident', incidentId] })
      onDone()
    } catch (error) {
      showFailure(form, error, 'correction')
    }
  })

  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="mt-2 flex flex-col gap-3 rounded-lg border bg-card p-3">
        <FormField
          control={form.control}
          name="correction"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Correction</FormLabel>
              <FormControl>
                <Textarea rows={2} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <p className="text-xs text-muted-foreground">The original entry stays as it is; the correction is added after it, linked to it.</p>
        {form.formState.errors.root && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {form.formState.errors.root.message}
          </p>
        )}
        <div className="flex gap-2">
          <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? 'Saving…' : 'Save correction'}
          </Button>
          <Button type="button" size="sm" variant="outline" onClick={onDone}>
            Cancel
          </Button>
        </div>
      </form>
    </Form>
  )
}

/**
 * Everything that happened, oldest first, as the server orders it. Entries are append-only: a correction is
 * a new entry linked to the one it corrects, and each side links to the other.
 */
export function IncidentTimeline({ incidentId, entries }: { incidentId: number; entries: TimelineEntry[] }) {
  const { user } = useAuth()
  const canWrite = can(user?.role, 'respond')
  const [correcting, setCorrecting] = useState<number | null>(null)
  const byId = new Map(entries.map((entry) => [entry.id, entry]))
  const correctionsOf = (entry: TimelineEntry) => entries.filter((other) => other.correctsEntryId === entry.id)

  return (
    <div className="flex flex-col gap-4">
      {entries.length === 0 ? (
        <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>
      ) : (
        <ol className="flex flex-col gap-4 border-l pl-5">
          {entries.map((entry) => {
            const original = entry.correctsEntryId === null ? undefined : byId.get(entry.correctsEntryId)
            const corrections = correctionsOf(entry)
            return (
              <li key={entry.id} id={anchor(entry)} aria-label={summarize(entry)} className="relative flex flex-col gap-0.5">
                <span aria-hidden="true" className="absolute top-1.5 -left-[25px] size-2 rounded-full bg-foreground" />
                <span className="text-sm">
                  <span className="font-semibold">{eventLabel(entry.eventType)}</span>
                  <span className="text-muted-foreground"> · {entry.actor ?? 'System'}</span>
                </span>
                {original && (
                  <span className="text-xs text-muted-foreground">
                    Corrects <a href={`#${anchor(original)}`}>{summarize(original)}</a>
                  </span>
                )}
                {entry.details && <span className="text-sm whitespace-pre-line">{entry.details}</span>}
                {corrections.length > 0 && (
                  <span className="text-xs text-muted-foreground">
                    Corrected by{' '}
                    {corrections.map((correction, index) => (
                      <span key={correction.id}>
                        {index > 0 && ', '}
                        <a href={`#${anchor(correction)}`}>{summarize(correction)}</a>
                      </span>
                    ))}
                  </span>
                )}
                <span className="flex items-center gap-3">
                  <span className="font-mono text-xs text-muted-foreground tnum">{formatIst(entry.recordedAt)}</span>
                  {canWrite && correcting !== entry.id && (
                    <button
                      type="button"
                      onClick={() => setCorrecting(entry.id)}
                      aria-label={`Correct ${summarize(entry)}`}
                      className="text-xs font-medium text-muted-foreground hover:text-foreground"
                    >
                      Correct
                    </button>
                  )}
                </span>
                {correcting === entry.id && <CorrectionForm incidentId={incidentId} entry={entry} onDone={() => setCorrecting(null)} />}
              </li>
            )
          })}
        </ol>
      )}
      {canWrite && <CommentForm incidentId={incidentId} />}
    </div>
  )
}
