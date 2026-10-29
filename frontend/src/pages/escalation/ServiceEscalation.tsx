import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { getEscalationPolicy, updateEscalationPolicy, type EscalationLevel } from '@/api/escalation'
import { getOnCall } from '@/api/onCall'
import { listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { EscalationLevelsForm } from './EscalationLevelsForm'

/** 90 → "1 h 30 min". */
function duration(minutes: number) {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest} min`
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

/**
 * Who is paged, in order, while nobody acknowledges (D14): each level its wait after the previous one,
 * then the fallback contact once, the last level's wait after the last level.
 */
function PagingOrder({ levels, fallback }: { levels: EscalationLevel[]; fallback: string | null }) {
  if (levels.length === 0) {
    return (
      <p className="rounded-lg border bg-card p-6 text-center text-muted-foreground">
        No escalation levels yet, so incidents on this service are not escalated.
      </p>
    )
  }
  const pagedAt = levels.reduce<number[]>((times, level) => [...times, (times.at(-1) ?? 0) + level.waitMinutes], [])
  const last = levels[levels.length - 1]
  return (
    <ol aria-label="Paging order" className="flex flex-col divide-y rounded-lg border bg-card">
      {levels.map((level, index) => (
        <li key={level.id} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 p-4">
          <div className="flex items-baseline gap-3">
            <span className="font-mono text-xs font-semibold text-muted-foreground">L{index + 1}</span>
            <span className="font-semibold">{level.responderDisplayName}</span>
          </div>
          <span className="text-sm text-muted-foreground">
            {duration(level.waitMinutes)} after {index === 0 ? 'the incident opens' : `level ${index}`} ·{' '}
            <span className="tnum">{duration(pagedAt[index])} in</span>
          </span>
        </li>
      ))}
      <li className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 p-4">
        <div className="flex items-baseline gap-3">
          <span className="font-mono text-xs font-semibold text-muted-foreground">Then</span>
          <span className="font-semibold">{fallback ?? 'No fallback contact'}</span>
        </div>
        <span className="text-sm text-muted-foreground">
          {fallback === null ? (
            'Set one on the Schedules page; until then nobody is paged after the last level'
          ) : (
            <>
              Fallback contact, once, {duration(last.waitMinutes)} after level {levels.length} ·{' '}
              <span className="tnum">{duration(pagedAt[pagedAt.length - 1] + last.waitMinutes)} in</span>
            </>
          )}
        </span>
      </li>
    </ol>
  )
}

/** One service's escalation levels. Everyone reads them; only Admins change them, and the server checks again. */
export function ServiceEscalation({ serviceId }: { serviceId: number }) {
  const { user } = useAuth()
  const isAdmin = can(user?.role, 'configure')
  const queryClient = useQueryClient()
  const policy = useQuery({ queryKey: ['escalation-policy', serviceId], queryFn: () => getEscalationPolicy(serviceId) })
  // The fallback contact is set with the schedule.
  const onCall = useQuery({ queryKey: ['on-call', serviceId], queryFn: () => getOnCall(serviceId) })
  // Contacts are users; that list is Admin-only, like editing.
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: isAdmin })

  if (policy.isPending) return <LoadingState message="Loading the escalation levels" />
  if (policy.isError) return <ErrorState message={policy.error.message} onRetry={() => policy.refetch()} />

  const levels = (policy.data?.steps ?? []).toSorted((a, b) => a.stepNumber - b.stepNumber)

  return (
    <div className="flex flex-col gap-6">
      <PagingOrder levels={levels} fallback={onCall.data?.fallbackContactUsername ?? null} />
      {isAdmin && (
        <EscalationLevelsForm
          // A save gives the policy a new time, which starts the form again from what was saved.
          key={policy.data?.updatedAt ?? 'none'}
          levels={levels}
          users={users.data ?? []}
          onSave={async (steps) => {
            const saved = await updateEscalationPolicy(serviceId, steps)
            queryClient.setQueryData(['escalation-policy', serviceId], saved)
            toast.success('Escalation levels saved.')
          }}
        />
      )}
    </div>
  )
}
