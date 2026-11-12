import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { ExternalLink } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { addEvidence, type TimelineEntry } from '@/api/incidents'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { showRequestErrors } from '@/lib/forms'
import { formatIst } from '@/lib/time'

// The server's limits and rule (EvidenceRequest, IncidentService.addEvidence); this only saves a round trip.
const evidenceSchema = z.object({
  label: z.string().trim().min(1, { error: 'Say what the link shows' }).max(200, { error: 'At most 200 characters' }),
  url: z
    .string()
    .trim()
    .max(2048, { error: 'At most 2,048 characters' })
    .refine((value) => /^https?:\/\/[^\s/@]+/i.test(value), { error: 'Use an http or https link' }),
})

type EvidenceValues = z.infer<typeof evidenceSchema>

/** The server records evidence as "label: url" on the timeline; this reads it back. */
function parseEvidence(entry: TimelineEntry) {
  const match = entry.details?.match(/^(.*): (https?:\/\/\S+)$/s)
  return match ? { label: match[1], url: match[2] } : null
}

function AddEvidenceForm({ incidentId }: { incidentId: number }) {
  const queryClient = useQueryClient()
  const form = useForm<EvidenceValues>({ resolver: zodResolver(evidenceSchema), defaultValues: { label: '', url: '' } })

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      await addEvidence(incidentId, values)
      form.reset({ label: '', url: '' })
      await queryClient.invalidateQueries({ queryKey: ['incident', incidentId] })
    } catch (error) {
      if (error instanceof ApiRequestError) showRequestErrors(form, error, ['label', 'url'])
      else form.setError('root', { message: 'Settle could not be reached. Try again in a moment.' })
    }
  })

  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3 rounded-lg border bg-card p-4">
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
          <FormField
            control={form.control}
            name="label"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Label</FormLabel>
                <FormControl>
                  <Input placeholder="Error spike dashboard" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="url"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Link</FormLabel>
                <FormControl>
                  <Input type="url" placeholder="https://" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </div>
        {form.formState.errors.root && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {form.formState.errors.root.message}
          </p>
        )}
        <Button type="submit" disabled={form.formState.isSubmitting} className="w-fit">
          {form.formState.isSubmitting ? 'Adding…' : 'Add evidence'}
        </Button>
      </form>
    </Form>
  )
}

/** Links to dashboards, traces, or tickets that explain the incident (FR-09.3). Allowed on CLOSED incidents too. */
export function IncidentEvidence({ incidentId, timeline }: { incidentId: number; timeline: TimelineEntry[] }) {
  const { user } = useAuth()
  const evidence = timeline
    .filter((entry) => entry.eventType === 'EVIDENCE_ADDED')
    .flatMap((entry) => {
      const parsed = parseEvidence(entry)
      return parsed ? [{ entry, ...parsed }] : []
    })

  return (
    <section aria-labelledby="evidence-heading" className="flex flex-col gap-3">
      <h2 id="evidence-heading" className="text-lg font-semibold">
        Evidence
      </h2>
      {evidence.length === 0 ? (
        <p className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">No evidence links yet.</p>
      ) : (
        <ul className="flex flex-col divide-y rounded-lg border bg-card">
          {evidence.map(({ entry, label, url }) => (
            <li key={entry.id} className="flex flex-col gap-0.5 p-4">
              <a href={url} target="_blank" rel="noopener noreferrer" className="inline-flex w-fit items-center gap-1.5 font-medium text-primary hover:underline">
                {label}
                <ExternalLink className="size-3.5" aria-hidden="true" />
              </a>
              <span className="text-xs break-all text-muted-foreground">{url}</span>
              <span className="text-xs text-muted-foreground">
                {entry.actor ?? 'System'} · <span className="font-mono tnum">{formatIst(entry.recordedAt)}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
      {can(user?.role, 'respond') && <AddEvidenceForm incidentId={incidentId} />}
    </section>
  )
}
