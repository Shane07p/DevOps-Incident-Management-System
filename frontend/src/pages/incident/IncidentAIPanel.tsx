import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { ApiRequestError } from '@/api/client'
import {
  decideSuggestedAction,
  generateIncidentDraft,
  getLatestInvestigation,
  runInvestigation,
} from '@/api/ai'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import { formatIst } from '@/lib/time'

export function IncidentAIPanel({ incidentId }: { incidentId: number }) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const canRespond = can(user?.role, 'respond')
  const queryKey = ['incident', incidentId, 'investigation']
  const [summaryDraft, setSummaryDraft] = useState('')

  const investigation = useQuery({
    queryKey,
    queryFn: async () => {
      try {
        return await getLatestInvestigation(incidentId)
      } catch (error) {
        if (error instanceof ApiRequestError && error.status === 404) {
          return null
        }
        throw error
      }
    },
    enabled: incidentId > 0,
    retry: false,
  })

  const investigate = useMutation({
    mutationFn: () => runInvestigation(incidentId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey })
    },
  })

  const catchMeUp = useMutation({
    mutationFn: () => generateIncidentDraft(incidentId, 'SUMMARY'),
    onSuccess: (result) => {
      setSummaryDraft(result.text)
    },
  })

  const decide = useMutation({
    mutationFn: ({
      actionId,
      decision,
    }: {
      actionId: number
      decision: 'ACCEPTED' | 'REJECTED'
    }) =>
      decideSuggestedAction(investigation.data!.id, actionId, decision),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey })
    },
  })

  const errorMessage = (error: unknown) =>
    error instanceof ApiRequestError && error.status === 503
      ? 'AI is currently unavailable. The rest of the incident page is still working.'
      : 'Could not load AI results. Please try again.'

  const draftErrorMessage =
    catchMeUp.error instanceof ApiRequestError &&
    catchMeUp.error.status === 503
      ? 'AI is currently unavailable. You can still use the rest of the incident page.'
      : 'Could not generate the summary. Please try again.'

  return (
    <section
      aria-labelledby="incident-ai-heading"
      className="flex flex-col gap-4 rounded-lg border bg-card p-4"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Sparkles className="size-5 text-accent-foreground" aria-hidden="true" />
          <h2 id="incident-ai-heading" className="text-lg font-semibold">
            AI incident investigation
          </h2>
        </div>
        <span
          data-testid="incident-ai-label"
          className="rounded-full border border-ring bg-accent px-2 py-1 text-xs font-medium text-accent-foreground"
        >
          AI-generated
        </span>
      </div>

      {canRespond && (
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            className="w-fit"
            disabled={investigate.isPending}
            onClick={() => investigate.mutate()}
          >
            {investigate.isPending ? 'Investigating...' : 'Investigate incident'}
          </Button>

          <Button
            type="button"
            variant="outline"
            disabled={catchMeUp.isPending}
            onClick={() => catchMeUp.mutate()}
          >
            {catchMeUp.isPending ? 'Generating summary...' : 'Catch me up'}
          </Button>
        </div>
      )}

      {catchMeUp.isError && (
        <p role="alert" className="text-sm text-destructive">
          {draftErrorMessage}
        </p>
      )}

      {summaryDraft && (
        <div className="flex flex-col gap-2">
          <h3 className="font-semibold">AI summary draft</h3>
          <span className="text-xs text-muted-foreground">AI-generated · Editable</span>
          <textarea
            aria-label="AI summary draft"
            className="min-h-32 w-full rounded-md border bg-background p-3 text-sm"
            value={summaryDraft}
            onChange={(event) => setSummaryDraft(event.target.value)}
            readOnly={!canRespond}
          />
        </div>
      )}

      {(investigation.isError || investigate.isError) && (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(investigation.error ?? investigate.error)}
        </p>
      )}

      {investigation.isPending && (
        <p className="text-sm text-muted-foreground">Loading investigation...</p>
      )}

      {investigation.data === null && !investigation.isError && (
        <p className="text-sm text-muted-foreground">
          No AI investigation is available yet.
          {canRespond ? ' Select Investigate incident to begin.' : ''}
        </p>
      )}

      {investigation.data && (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <h3 className="font-semibold">Investigation summary</h3>
            <p className="whitespace-pre-line text-sm">{investigation.data.summary}</p>
            <span className="text-xs text-muted-foreground">
              Generated {formatIst(investigation.data.createdAt)}
            </span>
          </div>

          <div className="flex flex-col gap-3">
            <h3 className="font-semibold">Ranked hypotheses</h3>
            {investigation.data.hypotheses.map((hypothesis) => (
              <article key={hypothesis.rank} className="rounded-lg border p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <h4 className="font-medium">
                    {hypothesis.rank}. {hypothesis.title}
                  </h4>
                  <span className="text-xs text-muted-foreground">
                    Confidence: {Math.round(hypothesis.confidence * 100)}%
                  </span>
                </div>
                <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
                  {hypothesis.evidence.map((item, index) => (
                    <li key={`${item.kind}-${index}`}>
                      <span className="font-medium">{item.kind}:</span> {item.text}
                    </li>
                  ))}
                </ul>
              </article>
            ))}
          </div>

          <div className="flex flex-col gap-3">
            <h3 className="font-semibold">Suggested actions</h3>
            {investigation.data.suggestedActions.map((action) => (
              <article key={action.id} className="flex flex-col gap-2 rounded-lg border p-3">
                <p className="text-sm">{action.text}</p>

                {action.decision ? (
                  <p className="text-xs text-muted-foreground">
                    {action.decision === 'ACCEPTED' ? 'Accepted' : 'Rejected'}
                    {action.decidedBy ? ` by ${action.decidedBy}` : ''}
                  </p>
                ) : canRespond ? (
                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="button"
                      size="sm"
                      disabled={decide.isPending}
                      onClick={() =>
                        decide.mutate({
                          actionId: action.id,
                          decision: 'ACCEPTED',
                        })
                      }
                    >
                      Accept
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      disabled={decide.isPending}
                      onClick={() =>
                        decide.mutate({
                          actionId: action.id,
                          decision: 'REJECTED',
                        })
                      }
                    >
                      Reject
                    </Button>
                  </div>
                ) : (
                  <p className="text-xs text-muted-foreground">Not decided yet</p>
                )}
              </article>
            ))}
          </div>

          {decide.isError && (
            <p role="alert" className="text-sm text-destructive">
              Could not record that decision. Please try again.
            </p>
          )}
        </div>
      )}
    </section>
  )
}
