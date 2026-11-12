import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { getIncident } from '@/api/incidents'
import { getPostmortem, savePostmortem, reviewPostmortem } from '@/api/postmortems'
import { useAuth } from '@/auth/AuthProvider'
import { Button } from '@/components/ui/button'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { IncidentTimeline } from './incident/IncidentTimeline'
import { PostmortemFollowUps } from './PostmortemFollowUps'

const fields = [
  ['summary', 'Summary'],
  ['impact', 'Impact'],
  ['rootCause', 'Root cause'],
  ['resolution', 'Resolution'],
  ['wentWell', 'What went well'],
  ['wentBadly', 'What went badly'],
] as const

type FormData = Record<(typeof fields)[number][0], string>

const emptyForm: FormData = {
  summary: '',
  impact: '',
  rootCause: '',
  resolution: '',
  wentWell: '',
  wentBadly: '',
}

export function PostmortemPage() {
  const id = Number(useParams().id)
  const valid = Number.isInteger(id) && id > 0
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const canEdit = user?.role === 'ADMIN' || user?.role === 'ENGINEER'

  const incident = useQuery({
    queryKey: ['incident', id],
    queryFn: () => getIncident(id),
    enabled: valid,
  })

  const postmortem = useQuery({
    queryKey: ['postmortem', id],
    queryFn: () => getPostmortem(id),
    enabled: valid,
    retry: false,
  })

  const [form, setForm] = useState<FormData>(emptyForm)
  const [message, setMessage] = useState('')

  useEffect(() => {
    if (postmortem.data) {
      setForm({
        summary: postmortem.data.summary,
        impact: postmortem.data.impact,
        rootCause: postmortem.data.rootCause,
        resolution: postmortem.data.resolution,
        wentWell: postmortem.data.wentWell,
        wentBadly: postmortem.data.wentBadly,
      })
    }
  }, [postmortem.data])

  const savedVersion = postmortem.data?.version ?? null
  const isMissing = postmortem.error instanceof ApiRequestError && postmortem.error.status === 404

  const save = useMutation({
    mutationFn: () => savePostmortem(id, { ...form, expectedVersion: savedVersion }),
    onSuccess: async () => {
      setMessage('Draft saved successfully.')
      await queryClient.invalidateQueries({ queryKey: ['postmortem', id] })
    },
    onError: (error) => {
      setMessage(error instanceof ApiRequestError && error.status === 409
        ? 'Someone else saved this postmortem first. Reload the page before editing.'
        : error.message)
    },
  })

  const review = useMutation({
    mutationFn: async () => {
      if (isMissing) {
        await savePostmortem(id, { ...form, expectedVersion: null })
      } else {
        await savePostmortem(id, { ...form, expectedVersion: savedVersion })
      }
      return reviewPostmortem(id)
    },
    onSuccess: async () => {
      setMessage('Postmortem marked as reviewed.')
      await queryClient.invalidateQueries({ queryKey: ['postmortem', id] })
      await queryClient.invalidateQueries({ queryKey: ['incident', id] })
    },
    onError: (error) => setMessage(error.message),
  })

  if (!valid) return <ErrorState title="Invalid incident" message="The incident ID is invalid." />
  if (incident.isPending || postmortem.isPending) return <LoadingState message="Loading postmortem" />
  if (incident.isError) return <ErrorState message={incident.error.message} onRetry={() => void incident.refetch()} />
  if (postmortem.isError && !isMissing) {
    return <ErrorState message={postmortem.error.message} onRetry={() => void postmortem.refetch()} />
  }

  const reviewed = postmortem.data?.status === 'REVIEWED'
  const busy = save.isPending || review.isPending

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <Link to={`/incidents/${id}`} className="text-sm text-muted-foreground hover:text-foreground">
        ← Back to incident
      </Link>

      <div>
        <p className="text-sm text-muted-foreground">{incident.data.reference}</p>
        <h1 className="text-3xl font-bold">Postmortem</h1>
        <p className="mt-1 text-muted-foreground">{incident.data.title}</p>
        <p className="mt-2 text-sm">Status: <strong>{postmortem.data?.status ?? 'NOT STARTED'}</strong></p>
      </div>

      <PostmortemFollowUps incidentId={id} canEdit={canEdit} />

      <div className="grid gap-6 lg:grid-cols-[1fr_0.8fr]">
        <section className="flex flex-col gap-4">
          {fields.map(([key, label]) => (
            <label key={key} className="flex flex-col gap-2 text-sm font-medium">
              {label}
              <textarea
                value={form[key]}
                disabled={!canEdit || busy}
                maxLength={10000}
                rows={4}
                onChange={(event) => setForm((current) => ({ ...current, [key]: event.target.value }))}
                className="rounded-md border bg-background p-3 font-normal"
                placeholder={`Enter ${label.toLowerCase()}...`}
              />
            </label>
          ))}

          {!canEdit && <p className="text-sm text-muted-foreground">You have read-only access.</p>}
          {reviewed && <p className="text-sm text-green-600">This postmortem has been reviewed.</p>}
          {message && <p role="alert" className="text-sm text-destructive">{message}</p>}

          {canEdit && (
            <div className="flex flex-wrap gap-3">
              <Button disabled={busy} onClick={() => save.mutate()}>
                {save.isPending ? 'Saving...' : 'Save draft'}
              </Button>
              <Button variant="outline" disabled={busy || reviewed} onClick={() => review.mutate()}>
                {review.isPending ? 'Reviewing...' : 'Mark reviewed'}
              </Button>
            </div>
          )}
        </section>

        <section className="flex flex-col gap-3">
          <h2 className="text-lg font-semibold">Incident timeline</h2>
          <IncidentTimeline incidentId={id} entries={incident.data.timeline} />
        </section>
      </div>
    </div>
  )
}

