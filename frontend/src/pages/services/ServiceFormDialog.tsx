import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useForm, type FieldPath } from 'react-hook-form'
import { toast } from 'sonner'
import { ApiRequestError } from '@/api/client'
import type { Criticality } from '@/api/schemas'
import { createService, updateService, type Service } from '@/api/services'
import { listUsers } from '@/api/users'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
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
  const form = useForm<ServiceFormValues>({ resolver: zodResolver(serviceFormSchema), defaultValues: emptyValues() })
  // Owners are users; the list is Admin-only, like this form.
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: open })

  // Start from the chosen service, or from the defaults, each time the dialog opens.
  useEffect(() => {
    if (open) form.reset(service ? valuesFrom(service) : emptyValues())
  }, [open, service, form])

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const saved = service ? await updateService(service.id, toRequest(values)) : await createService(toRequest(values))
      toast.success(service ? `${saved.name} is updated.` : `${saved.name} is in the catalog.`)
      await queryClient.invalidateQueries({ queryKey: ['services'] })
      onOpenChange(false)
    } catch (error) {
      if (!(error instanceof ApiRequestError)) {
        form.setError('root', { message: 'The service was not saved. Try again.' })
        return
      }
      if (error.status === 409) {
        form.setError('name', { message: error.message })
        return
      }
      // Field errors go under their input; any the form has no input for, or a bare message, go above the buttons.
      const unplaced: string[] = []
      for (const [field, message] of Object.entries(error.errors)) {
        if (FIELD_NAMES.has(field)) form.setError(field as FieldPath<ServiceFormValues>, { message })
        else unplaced.push(message)
      }
      if (unplaced.length > 0) form.setError('root', { message: unplaced.join(' ') })
      else if (Object.keys(error.errors).length === 0) form.setError('root', { message: error.message })
    }
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
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
            <SlaTargetGrid control={form.control} />
            {form.formState.errors.root && (
              <p role="alert" className="text-sm font-medium text-destructive">
                {form.formState.errors.root.message}
              </p>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
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
