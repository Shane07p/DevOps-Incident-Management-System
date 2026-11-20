import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'

import { ApiRequestError } from '@/api/client'
import { getIncident } from '@/api/incidents'
import {
  getPostmortem,
  savePostmortem,
  reviewPostmortem,
} from '@/api/postmortems'
import { useAuth } from '@/auth/AuthProvider'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel } from '@/components/app/Panel'

import { IncidentTimeline } from './IncidentTimeline'
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
type MessageType = 'success' | 'error' | 'info'

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
  const [messageType, setMessageType] = useState<MessageType>('info')

  const showMessage = (text: string, type: MessageType) => {
    setMessage(text)
    setMessageType(type)
  }

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

  const isMissing =
    postmortem.error instanceof ApiRequestError &&
    postmortem.error.status === 404

  const reviewed = postmortem.data?.status === 'REVIEWED'

  const isLastAuthor =
    !!postmortem.data && postmortem.data.author.id === user?.id

  const canReview =
    canEdit && !!postmortem.data && !reviewed && !isLastAuthor

  const refreshPostmortem = async () => {
    await queryClient.refetchQueries({
      queryKey: ['postmortem', id],
      exact: true,
    })
  }

  const save = useMutation({
    mutationFn: () =>
      savePostmortem(id, {
        ...form,
        expectedVersion: savedVersion,
      }),

    onSuccess: async () => {
      showMessage('Draft saved successfully.', 'success')

      await queryClient.invalidateQueries({
        queryKey: ['postmortem', id],
      })
    },

    onError: async (error) => {
      if (error instanceof ApiRequestError && error.status === 409) {
        await refreshPostmortem()

        showMessage(
          'This postmortem was changed by someone else. The latest saved version has been reloaded. Please review it before editing again.',
          'info',
        )
      } else {
        showMessage(error.message, 'error')
      }
    },
  })

  const review = useMutation({
    mutationFn: () => {
      if (savedVersion === null) {
        throw new Error('Save the postmortem before requesting a review.')
      }

      return reviewPostmortem(id, savedVersion)
    },

    onSuccess: async () => {
      showMessage('Postmortem marked as reviewed.', 'success')

      await queryClient.invalidateQueries({
        queryKey: ['postmortem', id],
      })

      await queryClient.invalidateQueries({
        queryKey: ['incident', id],
      })
    },

    onError: async (error) => {
      if (error instanceof ApiRequestError && error.status === 409) {
        await refreshPostmortem()

        showMessage(
          'The postmortem changed or cannot be reviewed in its current state. The latest saved version has been reloaded. Please check the content and reviewer permissions.',
          'info',
        )
      } else {
        showMessage(error.message, 'error')
      }
    },
  })

  if (!valid) {
    return (
      <ErrorState
        title="Invalid incident"
        message="The incident ID is invalid."
      />
    )
  }

  if (incident.isPending || postmortem.isPending) {
    return <LoadingState message="Loading postmortem" />
  }

  if (incident.isError) {
    return (
      <ErrorState
        message={incident.error.message}
        onRetry={() => void incident.refetch()}
      />
    )
  }

  if (postmortem.isError && !isMissing) {
    return (
      <ErrorState
        message={postmortem.error.message}
        onRetry={() => void postmortem.refetch()}
      />
    )
  }

  const busy = save.isPending || review.isPending

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <Link
        to={`/incidents/${id}`}
        className="w-fit text-sm text-muted-foreground hover:text-foreground"
      >
        ← Back to incident
      </Link>

      <PageHeader
        eyebrow={incident.data.reference}
        title="Postmortem"
        description={incident.data.title}
        actions={
          <span className="text-sm text-muted-foreground">
            Status:{' '}
            <strong>
              {postmortem.data?.status ?? 'NOT STARTED'}
            </strong>
          </span>
        }
      />

      <PostmortemFollowUps incidentId={id} canEdit={canEdit} />

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.8fr)]">
        <Panel title="Postmortem details">
          <div className="flex flex-col gap-5 p-5">
            {fields.map(([key, label]) => (
              <div key={key} className="flex flex-col gap-2">
                <Label htmlFor={key}>{label}</Label>
                <Textarea
                  id={key}
                  value={form[key]}
                  disabled={!canEdit || busy}
                  maxLength={10000}
                  rows={4}
                  onChange={(event) => {
                    setForm((current) => ({
                      ...current,
                      [key]: event.target.value,
                    }))
                    setMessage('')
                  }}
                  placeholder={`Enter ${label.toLowerCase()}...`}
                />
              </div>
            ))}

            {!canEdit && (
              <p className="text-sm text-muted-foreground">
                You have read-only access.
              </p>
            )}

            {reviewed && (
              <p className="text-sm text-muted-foreground">
                This postmortem has been reviewed.
                {canEdit && ' Saving changes sends it back to draft, and it needs another review.'}
              </p>
            )}

            {canEdit && !postmortem.data && (
              <p className="text-sm text-muted-foreground">
                Save a draft first. A different user must review it.
              </p>
            )}

            {canEdit && isLastAuthor && !reviewed && (
              <p className="text-sm text-muted-foreground">
                Someone other than the author must review this postmortem.
              </p>
            )}

            {message && (
              <p
                role={messageType === 'error' ? 'alert' : 'status'}
                className={
                  messageType === 'error'
                    ? 'text-sm text-destructive'
                    : 'text-sm text-muted-foreground'
                }
              >
                {message}
              </p>
            )}

            {canEdit && (
              <div className="flex flex-wrap gap-3">
                <Button
                  disabled={busy}
                  onClick={() => save.mutate()}
                >
                  {save.isPending ? 'Saving...' : 'Save draft'}
                </Button>

                {canReview && (
                  <Button
                    variant="outline"
                    disabled={busy}
                    onClick={() => review.mutate()}
                  >
                    {review.isPending ? 'Reviewing...' : 'Mark reviewed'}
                  </Button>
                )}
              </div>
            )}
          </div>
        </Panel>

        <Panel title="Incident timeline">
          <div className="p-5">
            <IncidentTimeline
              incidentId={id}
              entries={incident.data.timeline}
            />
          </div>
        </Panel>
      </div>
    </div>
  )
}
