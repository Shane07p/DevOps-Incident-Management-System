import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createFollowUp,
  getFollowUps,
  updateFollowUp,
  type FollowUp,
} from '@/api/postmortems'

type Props = {
  incidentId: number
  canEdit: boolean
}

export function PostmortemFollowUps({ incidentId, canEdit }: Props) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const [kind, setKind] = useState<'CORRECTIVE' | 'PREVENTIVE'>('CORRECTIVE')
  const [ownerId, setOwnerId] = useState('')
  const [dueDate, setDueDate] = useState('')
  const [message, setMessage] = useState('')

  const followUps = useQuery({
    queryKey: ['follow-ups', incidentId],
    queryFn: () => getFollowUps(incidentId),
  })

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['follow-ups', incidentId] })
    await queryClient.invalidateQueries({ queryKey: ['incident', incidentId] })
  }

  const add = useMutation({
    mutationFn: () => createFollowUp(incidentId, {
      title: title.trim(),
      kind,
      ownerId: Number(ownerId),
      dueDate,
    }),
    onSuccess: async () => {
      setTitle('')
      setOwnerId('')
      setDueDate('')
      setMessage('Follow-up added.')
      await refresh()
    },
    onError: (error) => setMessage(error.message),
  })

  const changeStatus = useMutation({
    mutationFn: ({ item, status }: {
      item: FollowUp
      status: 'OPEN' | 'IN_PROGRESS' | 'DONE'
    }) => updateFollowUp(item.id, {
      title: item.title,
      kind: item.kind,
      ownerId: item.owner.id,
      dueDate: item.dueDate,
      status,
    }),
    onSuccess: refresh,
    onError: (error) => setMessage(error.message),
  })

  const submit = () => {
    setMessage('')

    if (!title.trim() || !ownerId || !dueDate) {
      setMessage('Please fill in all follow-up fields.')
      return
    }

    if (!Number.isInteger(Number(ownerId)) || Number(ownerId) <= 0) {
      setMessage('Enter a valid owner ID.')
      return
    }

    if (dueDate < new Date().toISOString().slice(0, 10)) {
      setMessage('Due date cannot be in the past.')
      return
    }

    add.mutate()
  }

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold">Follow-ups</h2>

      {canEdit && (
        <div className="grid gap-3 rounded-lg border p-4">
          <h3 className="font-medium">Add follow-up</h3>
          <input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Follow-up title"
            maxLength={500}
            className="rounded-md border bg-background p-2"
          />
          <select
            value={kind}
            onChange={(event) => setKind(event.target.value as typeof kind)}
            className="rounded-md border bg-background p-2"
          >
            <option value="CORRECTIVE">Corrective</option>
            <option value="PREVENTIVE">Preventive</option>
          </select>
          <input
            type="number"
            min="1"
            value={ownerId}
            onChange={(event) => setOwnerId(event.target.value)}
            placeholder="Owner ID"
            className="rounded-md border bg-background p-2"
          />
          <input
            type="date"
            min={new Date().toISOString().slice(0, 10)}
            value={dueDate}
            onChange={(event) => setDueDate(event.target.value)}
            className="rounded-md border bg-background p-2"
          />
          <button
            type="button"
            disabled={add.isPending}
            onClick={submit}
            className="w-fit rounded-md bg-primary px-4 py-2 text-primary-foreground disabled:opacity-50"
          >
            {add.isPending ? 'Adding...' : 'Add follow-up'}
          </button>
        </div>
      )}

      {message && <p role="alert" className="text-sm text-destructive">{message}</p>}

      {followUps.isPending && <p className="text-sm text-muted-foreground">Loading follow-ups...</p>}
      {followUps.isError && (
        <p role="alert" className="text-sm text-destructive">{followUps.error.message}</p>
      )}

      {followUps.data?.length === 0 && (
        <p className="text-sm text-muted-foreground">No follow-ups yet.</p>
      )}

      {followUps.data?.map((item) => (
        <div key={item.id} className="flex flex-col gap-2 rounded-lg border p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <strong>{item.title}</strong>
            {item.overdue && item.status !== 'DONE' && (
              <span className="text-sm font-semibold text-destructive">Overdue</span>
            )}
          </div>
          <p className="text-sm text-muted-foreground">
            {item.kind} · Owner: {item.owner.displayName} · Due: {item.dueDate}
          </p>
          <p className="text-sm">Status: {item.status.replace('_', ' ')}</p>

          {canEdit && (
            <select
              value={item.status}
              disabled={changeStatus.isPending}
              onChange={(event) => changeStatus.mutate({
                item,
                status: event.target.value as 'OPEN' | 'IN_PROGRESS' | 'DONE',
              })}
              className="w-fit rounded-md border bg-background p-2 text-sm"
            >
              <option value="OPEN">Open</option>
              <option value="IN_PROGRESS">In progress</option>
              <option value="DONE">Done</option>
            </select>
          )}
        </div>
      ))}
    </section>
  )
}
