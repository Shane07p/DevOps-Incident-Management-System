import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { ApiRequestError } from '@/api/client'
import type { Criticality } from '@/api/schemas'
import { getServiceMap, updateDependencies } from '@/api/serviceMap'
import { createService, updateService, type Service } from '@/api/services'
import { listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { showRequestErrors } from '@/lib/forms'
import { SlaTargetGrid } from './SlaTargetGrid'
import { emptyValues, FIELD_NAMES, serviceFormSchema, toRequest, valuesFrom, type ServiceFormValues } from './serviceForm'

const CRITICALITIES: Criticality[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

/** Registers a service, or edits `service` when one is given. Only Admins reach it. */
export function ServiceFormDialog({
  service,
  open,
  onOpenChange,
}: {
  service: Service | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const { org } = useAuth()
  const form = useForm<ServiceFormValues>({ resolver: zodResolver(serviceFormSchema), defaultValues: emptyValues() })
  const publicVisible = useWatch({ control: form.control, name: 'publicVisible' })
  // Owners are users; the list is Admin-only, like this form.
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: open })
  // A service's dependencies come from the map; they are edited here and saved after the service (US-26).
  const map = useQuery({ queryKey: ['service-map'], queryFn: getServiceMap, enabled: open && service !== null })
  const savedDependsOn = service && map.data
    ? map.data.edges.filter((edge) => edge.from === service.id).map((edge) => edge.to).toSorted((a, b) => a - b)
    : null
  // Null until the Admin changes something; cleared whenever the dialog closes.
  const [editedDependsOn, setEditedDependsOn] = useState<number[] | null>(null)
  const dependsOn = editedDependsOn ?? savedDependsOn

  const close = (next: boolean) => {
    if (!next) setEditedDependsOn(null)
    onOpenChange(next)
  }

  const toggleDependency = (id: number, checked: boolean) => {
    const current = dependsOn ?? []
    setEditedDependsOn(checked ? [...current, id].toSorted((a, b) => a - b) : current.filter((other) => other !== id))
  }

  // Start from the chosen service, or from the defaults, each time the dialog opens.
  useEffect(() => {
    if (open) form.reset(service ? valuesFrom(service) : emptyValues())
  }, [open, service, form])

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const saved = service ? await updateService(service.id, toRequest(values)) : await createService(toRequest(values))
      if (service && editedDependsOn && savedDependsOn && editedDependsOn.join() !== savedDependsOn.join()) {
        try {
          await updateDependencies(service.id, editedDependsOn)
        } catch (error) {
          await queryClient.invalidateQueries({ queryKey: ['services'] })
          form.setError('root', {
            message: `${saved.name} is saved, but its dependencies are not: ${error instanceof Error ? error.message : 'try again'}.`,
          })
          return
        }
        await queryClient.invalidateQueries({ queryKey: ['service-map'] })
        await queryClient.invalidateQueries({ queryKey: ['blast-radius'] })
      }
      toast.success(service ? `${saved.name} is updated.` : `${saved.name} is in the catalog.`)
      await queryClient.invalidateQueries({ queryKey: ['services'] })
      close(false)
    } catch (error) {
      if (!(error instanceof ApiRequestError)) {
        form.setError('root', { message: 'The service was not saved. Try again.' })
        return
      }
      if (error.status === 409) {
        form.setError('name', { message: error.message })
        return
      }
      showRequestErrors(form, error, FIELD_NAMES)
    }
  })

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{service ? `Edit ${service.name}` : 'New service'}</DialogTitle>
          <DialogDescription>
            {service
              ? 'Open incidents keep the targets they started with; new incidents use these.'
              : 'Incidents are raised against a service and copy its targets when they are created.'}
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input autoComplete="off" {...field} />
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
                    <Textarea rows={2} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField
                control={form.control}
                name="ownerId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Owner</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange} disabled={!users.data}>
                      <FormControl>
                        <SelectTrigger className="w-full">
                          <SelectValue placeholder={users.isPending ? 'Loading users' : 'Choose an owner'} />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {users.data?.map((user) => (
                          <SelectItem key={user.id} value={String(user.id)}>
                            {user.displayName}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {users.isError && <FormDescription>Users could not be loaded: {users.error.message}</FormDescription>}
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="criticality"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Criticality</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <FormControl>
                        <SelectTrigger className="w-full">
                          <SelectValue placeholder="Choose a criticality" />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {CRITICALITIES.map((criticality) => (
                          <SelectItem key={criticality} value={criticality}>
                            <span className="capitalize">{criticality.toLowerCase()}</span>
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>
            <fieldset className="flex flex-col gap-3 rounded-lg border p-3">
              <legend className="px-1 text-sm font-medium">Public status page</legend>
              <FormField
                control={form.control}
                name="publicVisible"
                render={({ field }) => (
                  <FormItem className="flex items-center gap-2">
                    <FormControl>
                      <Checkbox checked={field.value} onCheckedChange={(checked) => field.onChange(checked === true)} />
                    </FormControl>
                    <FormLabel className="font-normal">Show this service on the status page</FormLabel>
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="publicName"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Public name</FormLabel>
                    <FormControl>
                      <Input placeholder="Card payments" disabled={!publicVisible} {...field} />
                    </FormControl>
                    <FormDescription>
                      Customers see this name, never the internal one.
                      {org && (
                        <>
                          {' '}
                          <Link to={`/status/${org.slug}`} target="_blank" rel="noreferrer" className="font-medium text-primary hover:underline">
                            View the status page
                          </Link>
                        </>
                      )}
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </fieldset>
            <SlaTargetGrid control={form.control} />
            {service && (
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1 text-sm font-medium">Depends on</legend>
                <p className="text-sm text-muted-foreground">
                  The services {service.name} needs. The service map and blast radius use these.
                </p>
                {map.isPending ? (
                  <p className="text-sm text-muted-foreground">Loading services</p>
                ) : map.isError ? (
                  <p className="text-sm text-muted-foreground">Dependencies could not be loaded: {map.error.message}</p>
                ) : map.data.nodes.length <= 1 ? (
                  <p className="text-sm text-muted-foreground">No other services yet.</p>
                ) : (
                  <div className="grid gap-2 sm:grid-cols-2">
                    {map.data.nodes
                      .filter((node) => node.id !== service.id)
                      .map((node) => (
                        <div key={node.id} className="flex items-center gap-2">
                          <Checkbox
                            id={`depends-on-${node.id}`}
                            checked={dependsOn?.includes(node.id) ?? false}
                            onCheckedChange={(checked) => toggleDependency(node.id, checked === true)}
                          />
                          <Label htmlFor={`depends-on-${node.id}`} className="font-normal">
                            {node.name}
                          </Label>
                        </div>
                      ))}
                  </div>
                )}
              </fieldset>
            )}
            {form.formState.errors.root && (
              <p role="alert" className="text-sm font-medium text-destructive">
                {form.formState.errors.root.message}
              </p>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => close(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {service ? 'Save changes' : 'Add service'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
