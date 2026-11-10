import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleAlert, TriangleAlert } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useForm, useWatch, type FieldValues, type UseFormReturn } from 'react-hook-form'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import {
  acknowledgeIncident,
  assignIncidentRole,
  changeSeverity,
  changeStatus,
  reassignIncident,
  type Incident,
} from '@/api/incidents'
import { incidentStatusSchema, severitySchema, type IncidentStatus, type Severity } from '@/api/schemas'
import { listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { showRequestErrors } from '@/lib/forms'
import { needsNote, nextStatuses } from './transitions'

type Dialogs = 'status' | 'severity' | 'reassign' | 'role'

/** Thrown after a 409, once the banner is up and the incident is being re-read, so a dialog just closes. */
class Conflict extends Error {}

const statusSchema = z
  .object({ status: z.union([incidentStatusSchema, z.literal('')]), note: z.string().trim().max(5000, { error: 'At most 5,000 characters' }) })
  .superRefine((values, context) => {
    if (values.status === '') context.addIssue({ code: 'custom', path: ['status'], message: 'Choose the new status' })
    else if (needsNote(values.status) && values.note === '')
      context.addIssue({ code: 'custom', path: ['note'], message: 'Say how the service recovered' })
  })

const severityFormSchema = z.object({
  severity: z.union([severitySchema, z.literal('')]).refine((value) => value !== '', { error: 'Choose the new severity' }),
  reason: z.string().trim().min(1, { error: 'Give a reason for the change' }).max(5000, { error: 'At most 5,000 characters' }),
})

const reassignSchema = z.object({ assigneeId: z.string().min(1, { error: 'Choose the new Lead' }) })

const roleSchema = z.object({
  role: z.enum(['COMMS', 'OPS']),
  userId: z.string().min(1, { error: 'Choose a person' }),
})

function ErrorText({ message }: { message?: string }) {
  return message ? (
    <p role="alert" className="text-sm font-medium text-destructive">
      {message}
    </p>
  ) : null
}

/** Puts a rejected request on its form, unless it was a 409, which the panel's banner already reports. */
function showFailure<T extends FieldValues>(form: UseFormReturn<T>, error: unknown, fields: string[]) {
  if (error instanceof Conflict) return true
  if (error instanceof ApiRequestError) showRequestErrors(form, error, fields)
  else form.setError('root', { message: 'Settle could not be reached. Try again in a moment.' })
  return false
}

function ActionDialog({
  title,
  description,
  open,
  onClose,
  children,
}: {
  title: string
  description: string
  open: boolean
  onClose: () => void
  children: ReactNode
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {children}
      </DialogContent>
    </Dialog>
  )
}

function Footer({ onClose, busy, label }: { onClose: () => void; busy: boolean; label: string }) {
  return (
    <DialogFooter>
      <Button type="button" variant="outline" onClick={onClose}>
        Cancel
      </Button>
      <Button type="submit" disabled={busy}>
        {label}
      </Button>
    </DialogFooter>
  )
}

function StatusForm({ incident, submit, onClose }: { incident: Incident; submit: Submit; onClose: () => void }) {
  const form = useForm<z.input<typeof statusSchema>>({ resolver: zodResolver(statusSchema), defaultValues: { status: '', note: '' } })
  const chosen = useWatch({ control: form.control, name: 'status' })
  const onSubmit = form.handleSubmit(async ({ status, note }) => {
    try {
      await submit(() => changeStatus(incident.id, { status: status as IncidentStatus, note, expectedVersion: incident.version }))
      onClose()
    } catch (error) {
      if (showFailure(form, error, ['status', 'note'])) onClose()
    }
  })
  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="status"
          render={({ field }) => (
            <FormItem>
              <FormLabel>New status</FormLabel>
              <Select value={field.value} onValueChange={field.onChange}>
                <FormControl>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="Choose the new status" />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  {nextStatuses(incident.status).map((status) => (
                    <SelectItem key={status} value={status}>
                      {status}
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
          name="note"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{chosen === 'RESOLVED' ? 'Recovery note' : 'Note (optional)'}</FormLabel>
              <FormControl>
                <Textarea rows={3} {...field} />
              </FormControl>
              {chosen === 'RESOLVED' && <FormDescription>Resolving stops the resolution clock.</FormDescription>}
              <FormMessage />
            </FormItem>
          )}
        />
        <ErrorText message={form.formState.errors.root?.message} />
        <Footer onClose={onClose} busy={form.formState.isSubmitting} label="Change status" />
      </form>
    </Form>
  )
}

function SeverityForm({ incident, submit, onClose }: { incident: Incident; submit: Submit; onClose: () => void }) {
  const form = useForm<z.input<typeof severityFormSchema>>({
    resolver: zodResolver(severityFormSchema),
    defaultValues: { severity: '', reason: '' },
  })
  const onSubmit = form.handleSubmit(async ({ severity, reason }) => {
    try {
      await submit(() => changeSeverity(incident.id, { severity: severity as Severity, reason, expectedVersion: incident.version }))
      onClose()
    } catch (error) {
      if (showFailure(form, error, ['severity', 'reason'])) onClose()
    }
  })
  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="severity"
          render={({ field }) => (
            <FormItem>
              <FormLabel>New severity</FormLabel>
              <Select value={field.value} onValueChange={field.onChange}>
                <FormControl>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="Choose the new severity" />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  {severitySchema.options
                    .filter((severity) => severity !== incident.severity)
                    .map((severity) => (
                      <SelectItem key={severity} value={severity}>
                        {severity}
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
          name="reason"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Reason</FormLabel>
              <FormControl>
                <Textarea rows={3} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <ErrorText message={form.formState.errors.root?.message} />
        <Footer onClose={onClose} busy={form.formState.isSubmitting} label="Change severity" />
      </form>
    </Form>
  )
}

interface Person {
  id: number
  displayName: string
}

function PersonSelect({ people, value, onChange, placeholder }: { people: Person[]; value: string; onChange: (value: string) => void; placeholder: string }) {
  return (
    <Select value={value} onValueChange={onChange}>
      <FormControl>
        <SelectTrigger className="w-full">
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
      </FormControl>
      <SelectContent>
        {people.map((person) => (
          <SelectItem key={person.id} value={String(person.id)}>
            {person.displayName}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function ReassignForm({ incident, people, submit, onClose }: { incident: Incident; people: Person[]; submit: Submit; onClose: () => void }) {
  const form = useForm<z.infer<typeof reassignSchema>>({ resolver: zodResolver(reassignSchema), defaultValues: { assigneeId: '' } })
  const choices = people.filter((person) => person.id !== incident.assignee?.id)
  const onSubmit = form.handleSubmit(async ({ assigneeId }) => {
    try {
      await submit(() => reassignIncident(incident.id, { assigneeId: Number(assigneeId), expectedVersion: incident.version }))
      onClose()
    } catch (error) {
      if (showFailure(form, error, ['assigneeId'])) onClose()
    }
  })
  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="assigneeId"
          render={({ field }) => (
            <FormItem>
              <FormLabel>New Lead</FormLabel>
              <PersonSelect people={choices} value={field.value} onChange={field.onChange} placeholder="Choose a person" />
              <FormMessage />
            </FormItem>
          )}
        />
        <ErrorText message={form.formState.errors.root?.message} />
        <Footer onClose={onClose} busy={form.formState.isSubmitting} label="Reassign" />
      </form>
    </Form>
  )
}

function RoleForm({ incident, people, submit, onClose }: { incident: Incident; people: Person[]; submit: Submit; onClose: () => void }) {
  const form = useForm<z.infer<typeof roleSchema>>({ resolver: zodResolver(roleSchema), defaultValues: { role: 'COMMS', userId: '' } })
  const onSubmit = form.handleSubmit(async ({ role, userId }) => {
    try {
      await submit(() => assignIncidentRole(incident.id, { role, userId: Number(userId), expectedVersion: incident.version }))
      onClose()
    } catch (error) {
      if (showFailure(form, error, ['role', 'userId'])) onClose()
    }
  })
  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="role"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Role</FormLabel>
              <Select value={field.value} onValueChange={field.onChange}>
                <FormControl>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  <SelectItem value="COMMS">Comms: keeps stakeholders updated</SelectItem>
                  <SelectItem value="OPS">Ops: investigates and fixes</SelectItem>
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="userId"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Person</FormLabel>
              <PersonSelect people={people} value={field.value} onChange={field.onChange} placeholder="Choose a person" />
              <FormMessage />
            </FormItem>
          )}
        />
        <ErrorText message={form.formState.errors.root?.message} />
        <Footer onClose={onClose} busy={form.formState.isSubmitting} label="Give role" />
      </form>
    </Form>
  )
}

type Submit = (change: () => Promise<Incident>) => Promise<void>

/**
 * What a responder can do to an incident. Each control shows only for the roles and statuses that allow it;
 * the server checks both again. Every change carries the version on screen, so a change made after someone
 * else's gets 409: the panel says so and re-reads the incident.
 */
export function IncidentActions({ incident }: { incident: Incident }) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState<Dialogs | null>(null)
  const [conflict, setConflict] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [acknowledging, setAcknowledging] = useState(false)
  const responder = can(user?.role, 'respond')
  const admin = can(user?.role, 'configure')
  // Only Admins can list the org's members (GET /api/users); an Engineer can take a role or the incident themselves.
  const members = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: admin })
  const people: Person[] = admin ? (members.data ?? []) : user ? [user] : []
  const closed = incident.status === 'CLOSED'
  const close = () => setOpen(null)

  const submit: Submit = async (change) => {
    setConflict(null)
    setFailure(null)
    try {
      const updated = await change()
      queryClient.setQueryData(['incident', incident.id], updated)
      await queryClient.invalidateQueries({ queryKey: ['incidents'] })
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 409) {
        setConflict(error.message)
        await queryClient.invalidateQueries({ queryKey: ['incident', incident.id] })
        throw new Conflict()
      }
      throw error
    }
  }

  const acknowledge = async () => {
    setAcknowledging(true)
    try {
      await submit(() => acknowledgeIncident(incident.id, incident.version))
    } catch (error) {
      if (!(error instanceof Conflict)) setFailure(error instanceof Error ? error.message : 'The incident was not acknowledged.')
    } finally {
      setAcknowledging(false)
    }
  }

  return (
    <section aria-labelledby="actions-heading" className="flex flex-col gap-3 rounded-lg border bg-card p-4">
      <h2 id="actions-heading" className="sr-only">
        Actions and roles
      </h2>

      {conflict && (
        <div role="alert" className="flex items-start gap-2.5 rounded-md bg-sev-1-bg px-3.5 py-3 text-sm text-sev-1-fg">
          <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <span>
            <span className="font-semibold">{incident.reference} changed while you were looking at it, so nothing was saved.</span> It now
            shows the latest version; check it and try again. ({conflict})
          </span>
        </div>
      )}
      {failure && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {failure}
        </p>
      )}

      <dl className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
        {(
          [
            ['Lead', incident.assignee],
            ['Comms', incident.roles.comms],
            ['Ops', incident.roles.ops],
          ] as const
        ).map(([label, person]) => (
          <div key={label} className="flex gap-1.5">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="font-medium">{person?.displayName ?? 'Nobody yet'}</dd>
          </div>
        ))}
      </dl>
      {incident.roleWarning && (
        <p className="flex items-center gap-2 rounded-md bg-sev-2-bg px-3 py-2 text-sm text-sev-2-fg">
          <TriangleAlert className="size-4 shrink-0" aria-hidden="true" />
          {incident.roleWarning}
        </p>
      )}

      {responder && (
        <div className="flex flex-wrap gap-2">
          {incident.status === 'OPEN' && (
            <Button onClick={acknowledge} disabled={acknowledging}>
              {acknowledging ? 'Acknowledging…' : 'Acknowledge'}
            </Button>
          )}
          {nextStatuses(incident.status).length > 0 && (
            <Button variant="outline" onClick={() => setOpen('status')}>
              Change status
            </Button>
          )}
          {!closed && (
            <>
              <Button variant="outline" onClick={() => setOpen('severity')}>
                Change severity
              </Button>
              <Button variant="outline" onClick={() => setOpen('reassign')}>
                Reassign
              </Button>
              <Button variant="outline" onClick={() => setOpen('role')}>
                Give a role
              </Button>
            </>
          )}
        </div>
      )}

      <ActionDialog
        title="Change status"
        description={`From ${incident.status}. Only the next steps allowed from here are listed.`}
        open={open === 'status'}
        onClose={close}
      >
        <StatusForm incident={incident} submit={submit} onClose={close} />
      </ActionDialog>
      <ActionDialog
        title="Change severity"
        description={`From ${incident.severity}. The SLA targets stay as they were set when the incident was declared.`}
        open={open === 'severity'}
        onClose={close}
      >
        <SeverityForm incident={incident} submit={submit} onClose={close} />
      </ActionDialog>
      <ActionDialog
        title="Reassign"
        description="The new Lead is notified. Neither SLA clock restarts."
        open={open === 'reassign'}
        onClose={close}
      >
        <ReassignForm incident={incident} people={people} submit={submit} onClose={close} />
      </ActionDialog>
      <ActionDialog
        title="Give a role"
        description="Comms or Ops. The Lead is whoever the incident is assigned to; change that with Reassign."
        open={open === 'role'}
        onClose={close}
      >
        <RoleForm incident={incident} people={people} submit={submit} onClose={close} />
      </ActionDialog>
    </section>
  )
}
