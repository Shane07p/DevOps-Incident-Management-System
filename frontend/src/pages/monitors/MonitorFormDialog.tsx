import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { createMonitor, updateMonitor, type Monitor } from '@/api/monitors'
import type { Service } from '@/api/services'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

const whole = (min: number, max: number, message: string) =>
  z.string().trim().refine((value) => /^\d+$/.test(value) && Number(value) >= min && Number(value) <= max, { error: message })

// The server's rules and messages (MonitorRequest); these only save a round trip.
const monitorFormSchema = z.object({
  name: z.string().trim().min(1, { error: 'Name is required' }).max(100, { error: 'Name must be at most 100 characters' }),
  url: z.string().trim().min(1, { error: 'URL is required' }).max(2048, { error: 'URL must be at most 2048 characters' })
    .refine((value) => /^https?:\/\/[^\s/]+/i.test(value), { error: 'Enter a valid http or https URL' }),
  serviceId: z.string().min(1, { error: 'Service is required' }),
  expectedStatus: whole(100, 599, 'Expected status must be between 100 and 599'),
  intervalSeconds: whole(30, 86400, 'Interval must be between 30 and 86400 seconds'),
  failureThreshold: whole(1, 100, 'Failure threshold must be between 1 and 100'),
  enabled: z.boolean(),
})

type MonitorFormValues = z.infer<typeof monitorFormSchema>

const FIELDS: (keyof MonitorFormValues)[] = ['name', 'url', 'serviceId', 'expectedStatus', 'intervalSeconds', 'failureThreshold', 'enabled']

function valuesFor(monitor: Monitor | null, services: Service[]): MonitorFormValues {
  return monitor
    ? {
        name: monitor.name,
        url: monitor.url,
        serviceId: String(monitor.serviceId),
        expectedStatus: String(monitor.expectedStatus),
        intervalSeconds: String(monitor.intervalSeconds),
        failureThreshold: String(monitor.failureThreshold),
        enabled: monitor.enabled,
      }
    : {
        name: '',
        url: '',
        serviceId: services[0] ? String(services[0].id) : '',
        expectedStatus: '200',
        intervalSeconds: '60',
        failureThreshold: '3',
        enabled: true,
      }
}

/** Creates a monitor, or changes one when `monitor` is given. Admins only; the server checks again. */
export function MonitorFormDialog({
  open,
  onOpenChange,
  monitor,
  services,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  monitor: Monitor | null
  services: Service[]
}) {
  const queryClient = useQueryClient()
  const form = useForm<MonitorFormValues>({ resolver: zodResolver(monitorFormSchema), defaultValues: valuesFor(monitor, services) })

  useEffect(() => {
    if (open) form.reset(valuesFor(monitor, services))
  }, [open, monitor, services, form])

  const onSubmit = form.handleSubmit(async (values) => {
    const request = {
      name: values.name,
      url: values.url,
      serviceId: Number(values.serviceId),
      expectedStatus: Number(values.expectedStatus),
      intervalSeconds: Number(values.intervalSeconds),
      failureThreshold: Number(values.failureThreshold),
      enabled: values.enabled,
    }
    try {
      await (monitor ? updateMonitor(monitor.id, request) : createMonitor(request))
      await queryClient.invalidateQueries({ queryKey: ['monitors'] })
      toast.success(monitor ? `${values.name} is updated.` : `${values.name} is checked from now on.`)
      onOpenChange(false)
    } catch (error) {
      if (error instanceof ApiRequestError && Object.keys(error.errors).length > 0) {
        for (const [field, message] of Object.entries(error.errors)) {
          form.setError(FIELDS.includes(field as keyof MonitorFormValues) ? (field as keyof MonitorFormValues) : 'root', { message })
        }
      } else {
        form.setError('root', { message: error instanceof Error ? error.message : 'The monitor was not saved. Try again.' })
      }
    }
  })

  const text = (name: 'name' | 'url' | 'expectedStatus' | 'intervalSeconds' | 'failureThreshold', label: string, hint?: string, inputMode?: 'numeric') => (
    <FormField
      control={form.control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input inputMode={inputMode} autoComplete="off" {...field} />
          </FormControl>
          {hint && <FormDescription>{hint}</FormDescription>}
          <FormMessage />
        </FormItem>
      )}
    />
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{monitor ? `Edit ${monitor.name}` : 'New monitor'}</DialogTitle>
          <DialogDescription>Settle requests the URL on every interval and raises an alert after the failures in a row you set.</DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
            {text('name', 'Name')}
            {text('url', 'URL', 'A public http or https address.')}
            <FormField
              control={form.control}
              name="serviceId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Service</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger className="w-full">
                        <SelectValue placeholder="Choose a service" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {services.map((service) => (
                        <SelectItem key={service.id} value={String(service.id)}>
                          {service.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            <div className="grid gap-4 sm:grid-cols-3">
              {text('expectedStatus', 'Expected status', undefined, 'numeric')}
              {text('intervalSeconds', 'Interval (seconds)', undefined, 'numeric')}
              {text('failureThreshold', 'Failures in a row', undefined, 'numeric')}
            </div>
            <FormField
              control={form.control}
              name="enabled"
              render={({ field }) => (
                <FormItem className="flex flex-row items-center gap-2">
                  <FormControl>
                    <Checkbox checked={field.value} onCheckedChange={(checked) => field.onChange(checked === true)} />
                  </FormControl>
                  <FormLabel className="font-normal">Check it now</FormLabel>
                </FormItem>
              )}
            />
            {form.formState.errors.root && (
              <p role="alert" className="text-sm text-destructive">{form.formState.errors.root.message}</p>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>{monitor ? 'Save monitor' : 'Create monitor'}</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
