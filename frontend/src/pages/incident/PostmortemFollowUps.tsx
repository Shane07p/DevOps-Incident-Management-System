import { useEffect, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import {
  createFollowUp,
  getFollowUps,
  updateFollowUp,
  type FollowUp,
} from '@/api/postmortems'
import { listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

const followUpSchema = z.object({
  title: z.string().trim().min(1, 'Enter a follow-up title').max(500),
  kind: z.enum(['CORRECTIVE', 'PREVENTIVE']),
  ownerId: z.string().min(1, 'Select an owner'),
  dueDate: z.string().min(1, 'Select a due date'),
})

type FollowUpValues = z.infer<typeof followUpSchema>

type Props = {
  incidentId: number
  canEdit: boolean
}

export function PostmortemFollowUps({ incidentId, canEdit }: Props) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const isAdmin = can(user?.role, 'configure')
  const [message, setMessage] = useState('')

  // Calculate today's date once when the component initializes.
  const [today] = useState(() => new Date().toISOString().slice(0, 10))

  const form = useForm<FollowUpValues>({
    resolver: zodResolver(followUpSchema),
    defaultValues: {
      title: '',
      kind: 'CORRECTIVE',
      ownerId: user?.id ? String(user.id) : '',
      dueDate: '',
    },
  })

  const followUps = useQuery({
    queryKey: ['follow-ups', incidentId],
    queryFn: () => getFollowUps(incidentId),
  })

  // Only Admins can load the organisation's member list.
  const users = useQuery({
    queryKey: ['users'],
    queryFn: listUsers,
    enabled: isAdmin,
  })

  // Keep the current user as the default owner.
  useEffect(() => {
    if (user?.id && !form.getValues('ownerId')) {
      form.setValue('ownerId', String(user.id))
    }
  }, [user?.id, form])

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['follow-ups', incidentId] })
    await queryClient.invalidateQueries({ queryKey: ['incident', incidentId] })
  }

  const add = useMutation({
    mutationFn: (values: FollowUpValues) =>
      createFollowUp(incidentId, {
        title: values.title.trim(),
        kind: values.kind,
        ownerId: Number(values.ownerId),
        dueDate: values.dueDate,
      }),
    onSuccess: async () => {
      form.reset({
        title: '',
        kind: 'CORRECTIVE',
        ownerId: user?.id ? String(user.id) : '',
        dueDate: '',
      })
      setMessage('Follow-up added.')
      await refresh()
    },
    onError: (error) => setMessage(error.message),
  })

  const changeStatus = useMutation({
    mutationFn: ({
      item,
      status,
    }: {
      item: FollowUp
      status: 'OPEN' | 'IN_PROGRESS' | 'DONE'
    }) =>
      updateFollowUp(item.id, {
        title: item.title,
        kind: item.kind,
        ownerId: item.owner.id,
        dueDate: item.dueDate,
        status,
      }),
    onSuccess: refresh,
    onError: (error) => setMessage(error.message),
  })

  const submit = form.handleSubmit((values) => {
    setMessage('')

    if (values.dueDate < today) {
      form.setError('dueDate', {
        message: 'Due date cannot be in the past.',
      })
      return
    }

    add.mutate(values)
  })

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold">Follow-ups</h2>

      {canEdit && (
        <Form {...form}>
          <form
            onSubmit={submit}
            noValidate
            className="grid gap-3 rounded-lg border p-4"
          >
            <h3 className="font-medium">Add follow-up</h3>

            <FormField
              control={form.control}
              name="title"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Follow-up title</FormLabel>
                  <FormControl>
                    <Input
                      id="title"
                      placeholder="Enter follow-up title"
                      maxLength={500}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="kind"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Type</FormLabel>
                  <Select
                    value={field.value}
                    onValueChange={field.onChange}
                  >
                    <FormControl>
                      <SelectTrigger id="kind" className="w-full">
                        <SelectValue placeholder="Select type" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      <SelectItem value="CORRECTIVE">Corrective</SelectItem>
                      <SelectItem value="PREVENTIVE">Preventive</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="ownerId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Owner</FormLabel>
                  {isAdmin ? (
                    <Select
                      value={field.value}
                      onValueChange={field.onChange}
                      disabled={users.isPending || users.isError}
                    >
                      <FormControl>
                        <SelectTrigger id="ownerId" className="w-full">
                          <SelectValue placeholder="Select an owner" />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {(users.data ?? [])
                          .filter((member) => member.enabled)
                          .map((member) => (
                            <SelectItem
                              key={member.id}
                              value={String(member.id)}
                            >
                              {member.displayName}
                            </SelectItem>
                          ))}
                      </SelectContent>
                    </Select>
                  ) : (
                    <FormControl>
                      <Input
                        id="ownerId"
                        value={user?.displayName ?? 'Current user'}
                        disabled
                      />
                    </FormControl>
                  )}
                  {users.isError && isAdmin && (
                    <p role="alert" className="text-sm text-destructive">
                      Could not load users: {users.error.message}
                    </p>
                  )}
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="dueDate"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Due date</FormLabel>
                  <FormControl>
                    <Input
                      id="dueDate"
                      type="date"
                      min={today}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Button
              type="submit"
              disabled={add.isPending || (isAdmin && users.isPending)}
              className="w-fit"
            >
              {add.isPending ? 'Adding...' : 'Add follow-up'}
            </Button>
          </form>
        </Form>
      )}

      {message && (
        <p role="alert" className="text-sm text-destructive">
          {message}
        </p>
      )}

      {followUps.isPending && (
        <p className="text-sm text-muted-foreground">
          Loading follow-ups...
        </p>
      )}

      {followUps.isError && (
        <p role="alert" className="text-sm text-destructive">
          {followUps.error.message}
        </p>
      )}

      {followUps.data?.length === 0 && (
        <p className="text-sm text-muted-foreground">
          No follow-ups yet.
        </p>
      )}

      {followUps.data?.map((item) => (
        <div key={item.id} className="flex flex-col gap-2 rounded-lg border p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <strong>{item.title}</strong>
            {item.overdue && item.status !== 'DONE' && (
              <span className="text-sm font-semibold text-destructive">
                Overdue
              </span>
            )}
          </div>

          <p className="text-sm text-muted-foreground">
            {item.kind} · Owner: {item.owner.displayName} · Due: {item.dueDate}
          </p>

          <p className="text-sm">
            Status: {item.status.replace('_', ' ')}
          </p>

          {canEdit && (
            <Select
              value={item.status}
              disabled={changeStatus.isPending}
              onValueChange={(value) =>
                changeStatus.mutate({
                  item,
                  status: value as 'OPEN' | 'IN_PROGRESS' | 'DONE',
                })
              }
            >
              <SelectTrigger className="w-fit">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="OPEN">Open</SelectItem>
                <SelectItem value="IN_PROGRESS">In progress</SelectItem>
                <SelectItem value="DONE">Done</SelectItem>
              </SelectContent>
            </Select>
          )}
        </div>
      ))}
    </section>
  )
}
