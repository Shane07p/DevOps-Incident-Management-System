import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, GitCommitHorizontal } from 'lucide-react'
import { linkDeploy, type Incident } from '@/api/incidents'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import { formatIst } from '@/lib/time'

/** The timeline entry the server writes when a deploy is linked (IncidentService.linkDeployment). */
const linkedDetails = (incident: Incident, deploy: Incident['deploys'][number]) =>
  `Linked deployment ${deploy.version} (${deploy.commit}) for ${incident.service.name}`

/** "35 min before", from the deploy to the incident's declaration. */
function before(deployedAt: string, createdAt: string) {
  const minutes = Math.round((Date.parse(createdAt) - Date.parse(deployedAt)) / 60_000)
  if (minutes < 0) return 'after it was declared'
  if (minutes < 60) return `${minutes} min before`
  const hours = Math.floor(minutes / 60)
  return minutes % 60 === 0 ? `${hours} h before` : `${hours} h ${minutes % 60} min before`
}

/**
 * Deploys to the incident's service in the 2 hours before it was declared, and any linked since (FR-09).
 * Engineers and Admins link one as a likely cause; linking is recorded on the timeline.
 */
export function IncidentDeploys({ incident }: { incident: Incident }) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const canLink = can(user?.role, 'respond')
  const linked = new Set(incident.timeline.filter((entry) => entry.eventType === 'DEPLOY_LINKED').map((entry) => entry.details))

  const link = useMutation({
    mutationFn: (deployId: number) => linkDeploy(incident.id, deployId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['incident', incident.id] }),
  })

  return (
    <section aria-labelledby="deploys-heading" className="flex flex-col gap-3">
      <h2 id="deploys-heading" className="text-lg font-semibold">
        Recent deploys
      </h2>
      {incident.deploys.length === 0 ? (
        <p className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
          No deploys to {incident.service.name} in the 2 hours before this incident.
        </p>
      ) : (
        <ul className="flex flex-col divide-y rounded-lg border bg-card">
          {incident.deploys.map((deploy) => {
            const isLinked = linked.has(linkedDetails(incident, deploy))
            return (
              <li key={deploy.id} className="flex flex-wrap items-center justify-between gap-3 p-4">
                <div className="flex min-w-0 items-start gap-3">
                  <GitCommitHorizontal className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="font-medium">
                      {deploy.version} <span className="font-mono text-xs text-muted-foreground">{deploy.commit.slice(0, 7)}</span>
                    </span>
                    <span className="text-sm text-muted-foreground">
                      {deploy.author} · <span className="font-mono tnum">{formatIst(deploy.deployedAt)}</span> ·{' '}
                      {before(deploy.deployedAt, incident.createdAt)}
                    </span>
                  </div>
                </div>
                {isLinked ? (
                  <span className="inline-flex items-center gap-1 text-sm font-medium text-muted-foreground">
                    <Check className="size-4" aria-hidden="true" />
                    Linked
                  </span>
                ) : (
                  canLink && (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      disabled={link.isPending}
                      aria-label={`Link ${deploy.version}`}
                      onClick={() => link.mutate(deploy.id)}
                    >
                      {link.isPending && link.variables === deploy.id ? 'Linking…' : 'Link'}
                    </Button>
                  )
                )}
              </li>
            )
          })}
        </ul>
      )}
      {link.isError && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {link.error.message}
        </p>
      )}
    </section>
  )
}
