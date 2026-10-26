import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, type FieldPath } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { createIncident, listServiceChoices } from '@/api/incidents'
import { severitySchema, type Severity } from '@/api/schemas'
import { SeverityBadge } from '@/components/app/badges'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'

const SEVERITIES = severitySchema.options

// What each level means (DR-01), so the reporter picks by impact.
const SEVERITY_MEANING: Record<Severity, string> = {
  SEV1: 'Critical service or business impact',
  SEV2: 'High impact, needs urgent attention',
  SEV3: 'Moderate impact',
  SEV4: 'Low impact',
}

// The server checks the same rules; these only save a round trip. Service and severity start empty
// so the reporter chooses them; the length checks keep '' in the form's type (see serviceForm.ts).
const newIncidentSchema = z.object({
  title: z.string().trim().min(1, { error: 'Enter a title' }).max(200, { error: 'Use at most 200 characters' }),
  description: z.string().trim().min(1, { error: 'Describe what is happening' }),
  serviceId: z.string().min(1, { error: 'Choose a service' }),
  severity: z.union([severitySchema, z.literal('')]).refine((value) => value.length > 0, { error: 'Choose a severity' }),
})

type NewIncidentValues = z.infer<typeof newIncidentSchema>

const EMPTY: NewIncidentValues = { title: '', description: '', serviceId: '', severity: '' }

/** Declares an incident. The route is for Admins and Engineers; the server checks again. */
export function NewIncidentPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  // Its own key under 'services', so saving a service refreshes it without sharing the catalog's cache.
  const services = useQuery({ queryKey: ['services', 'choices'], queryFn: listServiceChoices })
  const form = useForm<NewIncidentValues>({ resolver: zodResolver(newIncidentSchema), defaultValues: EMPTY })

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const incident = await createIncident({
        title: values.title,
        description: values.description,
        serviceId: Number(values.serviceId),
        severity: values.severity as Severity,
      })
      toast.success(`${incident.reference} is declared.`)
      await queryClient.invalidateQueries({ queryKey: ['incidents'] })
      navigate(`/incidents/${incident.id}`)
    } catch (error) {
      if (!(error instanceof ApiRequestError)) {
        form.setError('root', { message: 'The incident was not declared. Try again.' })
        return
      }
      // Field errors go under their input; any the form has no input for, or a bare message, go above the buttons.
      const unplaced: string[] = []
      for (const [field, message] of Object.entries(error.errors)) {
        if (field in EMPTY) form.setError(field as FieldPath<NewIncidentValues>, { message })
        else unplaced.push(message)
      }
      if (unplaced.length > 0) form.setError('root', { message: unplaced.join(' ') })
      else if (Object.keys(error.errors).length === 0) form.setError('root', { message: error.message })
    }
  })

  return (
    <div className="mx-auto flex max-w-[720px] flex-col gap-6">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
          Declare incident
        </h1>
        <p className="text-muted-foreground">
          It starts OPEN, with its response and resolution clocks running from now. The service's targets are copied and stay fixed.
        </p>
      </div>

      <Form {...form}>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4 rounded-lg border bg-card p-6">
          <FormField
            control={form.control}
            name="title"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Title</FormLabel>
                <FormControl>
                  <Input autoComplete="off" placeholder="e.g. Checkout failing for card payments" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="description"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Description</FormLabel>
                <FormControl>
                  <Textarea rows={4} placeholder="What is broken, who is affected, and since when" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="serviceId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Service</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange} disabled={!services.data}>
                    <FormControl>
                      <SelectTrigger className="w-full">
                        <SelectValue placeholder={services.isPending ? 'Loading services' : 'Choose a service'} />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {services.data?.map((service) => (
                        <SelectItem key={service.id} value={String(service.id)}>
                          {service.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {services.isError && <FormDescription>Services could not be loaded: {services.error.message}</FormDescription>}
                  {services.data?.length === 0 && (
                    <FormDescription>
                      There are no services yet. An Admin adds them on the <Link to="/services" className="underline">Services</Link> page.
                    </FormDescription>
                  )}
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="severity"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Severity</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger className="w-full">
                        <SelectValue placeholder="Choose a severity" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {SEVERITIES.map((severity) => (
                        <SelectItem key={severity} value={severity}>
                          <SeverityBadge severity={severity} />
                          <span className="text-muted-foreground">{SEVERITY_MEANING[severity]}</span>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
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
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" asChild>
              <Link to="/incidents">Cancel</Link>
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Declare incident
            </Button>
          </div>
        </form>
      </Form>
    </div>
  )
}
