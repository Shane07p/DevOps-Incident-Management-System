import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { createIncident } from '@/api/incidents'
import { listServices } from '@/api/services'
import { severitySchema, type Severity } from '@/api/schemas'
import { SeverityBadge } from '@/components/app/badges'
import { DuplicateIncidentWarning } from '@/components/app/DuplicateIncidentWarning'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { showRequestErrors } from '@/lib/forms'
import { formatMinutes } from '@/lib/time'

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

type ServiceWithTargets = Awaited<ReturnType<typeof listServices>>[number]

/** The chosen severity's meaning and the chosen service's targets for it, which the incident copies and keeps. */
function WhatHappensNext({ service, severity }: { service?: ServiceWithTargets; severity: Severity | '' }) {
  if (!severity) return null
  const target = service?.targets[severity]
  return (
    <section aria-label="What happens next" className="flex flex-col gap-3 rounded-lg border bg-muted/40 p-4 text-[13px]">
      <h3 className="text-[12px] font-medium text-muted-foreground">What happens next</h3>
      <p>{SEVERITY_MEANING[severity]}.</p>
      {target ? (
        <>
          <dl className="grid grid-cols-2 gap-4 border-t pt-3">
            <div className="flex flex-col gap-1">
              <dt className="text-[12px] text-muted-foreground">Response target</dt>
              <dd className="font-mono text-[18px] tnum">{formatMinutes(target.responseMinutes)}</dd>
            </div>
            <div className="flex flex-col gap-1">
              <dt className="text-[12px] text-muted-foreground">Resolution target</dt>
              <dd className="font-mono text-[18px] tnum">
                {target.resolutionMinutes === null ? 'Not set' : formatMinutes(target.resolutionMinutes)}
              </dd>
            </div>
          </dl>
          <p className="text-[12px] text-muted-foreground">
            Both clocks start when you declare the incident. {service?.name}'s targets are copied now and stay fixed.
          </p>
        </>
      ) : (
        <p className="text-[12px] text-muted-foreground">Choose a service to see its response and resolution targets.</p>
      )}
    </section>
  )
}

/**
 * The declare form, shared by the dialog (from anywhere in the app) and the /incidents/new page. Opens the
 * new incident once it is declared. Admins and Engineers only; the server checks again.
 */
export function DeclareIncidentForm({ onCancel, onDeclared }: { onCancel: () => void; onDeclared?: () => void }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const form = useForm<NewIncidentValues>({ resolver: zodResolver(newIncidentSchema), defaultValues: EMPTY })
  const titleValue = form.watch('title')
  const serviceIdValue = form.watch('serviceId')
  const severityValue = form.watch('severity')

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
      onDeclared?.()
      navigate(`/incidents/${incident.id}`)
    } catch (error) {
      if (!(error instanceof ApiRequestError)) {
        form.setError('root', { message: 'The incident was not declared. Try again.' })
        return
      }
      showRequestErrors(form, error, Object.keys(EMPTY))
    }
  })

  return (
      <Form {...form}>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
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
          <DuplicateIncidentWarning title={titleValue} serviceId={serviceIdValue} />
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
          <WhatHappensNext service={services.data?.find((service) => String(service.id) === serviceIdValue)} severity={severityValue} />
          {form.formState.errors.root && (
            <p role="alert" className="text-sm font-medium text-destructive">
              {form.formState.errors.root.message}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={onCancel}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Declare incident
            </Button>
          </div>
        </form>
      </Form>
  )
}
