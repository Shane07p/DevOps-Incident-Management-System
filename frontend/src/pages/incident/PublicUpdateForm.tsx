import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { PUBLIC_UPDATE_LABEL, PUBLIC_UPDATE_STATUSES, postPublicUpdate, publicUpdateStatusSchema } from '@/api/publicStatus'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { showRequestErrors } from '@/lib/forms'

// The server checks the same limit (PublicUpdateRequest); this only saves a round trip.
const MAX_LENGTH = 2000

const publicUpdateFormSchema = z.object({
  status: publicUpdateStatusSchema,
  message: z.string().trim().min(1, { error: 'Write what customers should know' }).max(MAX_LENGTH, { error: 'At most 2,000 characters' }),
})

type PublicUpdateFormValues = z.infer<typeof publicUpdateFormSchema>

/**
 * Customer-facing updates, kept apart from internal comments (FR-21). The incident's Lead or Comms, or an
 * Admin, may post; everyone else sees only where the page is.
 */
export function PublicUpdates({ incidentId, orgSlug, mayPublish }: { incidentId: number; orgSlug?: string; mayPublish: boolean }) {
  return (
    <section aria-labelledby="public-updates-heading" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="public-updates-heading" className="text-lg font-semibold">
          Public updates
        </h2>
        {orgSlug && (
          <Link to={`/status/${orgSlug}`} className="text-sm font-medium text-primary hover:underline">
            View the public status page
          </Link>
        )}
      </div>
      {mayPublish ? (
        <PublicUpdateForm incidentId={incidentId} />
      ) : (
        <p className="text-sm text-muted-foreground">The incident&apos;s Lead or Comms, or an Admin, posts what customers see.</p>
      )}
    </section>
  )
}

function PublicUpdateForm({ incidentId }: { incidentId: number }) {
  const queryClient = useQueryClient()
  const form = useForm<PublicUpdateFormValues>({
    resolver: zodResolver(publicUpdateFormSchema),
    defaultValues: { status: 'INVESTIGATING', message: '' },
  })

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      await postPublicUpdate(incidentId, values)
      form.reset({ status: values.status, message: '' })
      toast.success('Posted to the status page')
      // The update also lands on the timeline.
      await queryClient.invalidateQueries({ queryKey: ['incident', incidentId] })
    } catch (error) {
      if (error instanceof ApiRequestError) showRequestErrors(form, error, ['status', 'message'])
      else form.setError('root', { message: 'Settle could not be reached. Try again in a moment.' })
    }
  })

  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3 rounded-lg border bg-card p-4">
        <FormField
          control={form.control}
          name="status"
          render={({ field }) => (
            <FormItem className="sm:max-w-[240px]">
              <FormLabel>What customers see</FormLabel>
              <Select value={field.value} onValueChange={field.onChange}>
                <FormControl>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  {PUBLIC_UPDATE_STATUSES.map((status) => (
                    <SelectItem key={status} value={status}>
                      {PUBLIC_UPDATE_LABEL[status]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="message"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Public message</FormLabel>
              <FormControl>
                <Textarea rows={3} placeholder="What is affected, what you are doing, and when the next update is" {...field} />
              </FormControl>
              <FormDescription>Customers read this on the status page. Leave out internal names and details.</FormDescription>
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
          {form.formState.isSubmitting ? 'Posting…' : 'Post public update'}
        </Button>
      </form>
    </Form>
  )
}
