import { useQuery, useQueryClient } from '@tanstack/react-query'
import { BellRing, ShieldCheck } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { getEscalationPolicy, updateEscalationPolicy, type EscalationLevel } from '@/api/escalation'
import { listIncidents } from '@/api/incidents'
import { getOnCall } from '@/api/onCall'
import type { Service } from '@/api/services'
import { listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { Button } from '@/components/ui/button'
import { formatMinutes } from '@/lib/time'
import { useNow } from '@/lib/useNow'
import { escalationNow } from './escalating'
import { EscalationLevelsForm } from './EscalationLevelsForm'

function Step({ marker, when, name, note }: { marker: string; when: ReactNode; name: string; note?: string }) {
  return (
    <li className="flex items-start gap-4 px-[19px] py-[18px]">
      <span className="grid size-9 shrink-0 place-items-center rounded-full border font-mono text-[12px] text-muted-foreground">{marker}</span>
      <div className="flex min-w-0 flex-col gap-2">
        <span className="text-[13px] text-muted-foreground">{when}</span>
        <span className="flex items-center gap-2.5">
          <Avatar name={name} size="sm" />
          <span className="flex flex-col">
            <strong className="text-[13px] font-medium">{name}</strong>
            {note && <span className="text-[12px] text-muted-foreground">{note}</span>}
          </span>
        </span>
      </div>
    </li>
  )
}

/**
 * Who is paged, in order, while nobody acknowledges (D14): each level its wait after the previous one,
 * then the fallback contact once, the last level's wait after the last level.
 */
function PagingOrder({ service, levels, fallback }: { service: Service; levels: EscalationLevel[]; fallback: string | null }) {
  if (levels.length === 0) {
    return (
      <p className="rounded-lg border bg-card p-6 text-center text-[13px] text-muted-foreground">
        No escalation levels yet, so incidents on this service are not escalated.
      </p>
    )
  }
  const pagedAt = levels.reduce<number[]>((times, level) => [...times, (times.at(-1) ?? 0) + level.waitMinutes], [])
  const last = levels[levels.length - 1]
  return (
    <Panel title={`${service.name} · policy`}>
      <ol aria-label="Paging order" className="flex flex-col divide-y border-t">
        {levels.map((level, index) => (
          <Step
            key={level.id}
            marker={String(index + 1)}
            name={level.responderDisplayName}
            when={
              <>
                {formatMinutes(level.waitMinutes)} after {index === 0 ? 'the incident opens' : `level ${index}`} ·{' '}
                <span className="tnum">{formatMinutes(pagedAt[index])} in</span>
              </>
            }
          />
        ))}
        {fallback === null ? (
          <li className="flex items-start gap-4 px-[19px] py-[18px]">
            <span className="grid size-9 shrink-0 place-items-center rounded-full border text-[12px] text-muted-foreground">Then</span>
            <div className="flex flex-col gap-1 text-[13px]">
              <strong className="font-medium">No fallback contact</strong>
              <span className="text-muted-foreground">Set one on the Schedules page; until then nobody is paged after the last level</span>
            </div>
          </li>
        ) : (
          <Step
            marker="Then"
            name={fallback}
            note="Service fallback"
            when={
              <>
                Fallback contact, once, {formatMinutes(last.waitMinutes)} after level {levels.length} ·{' '}
                <span className="tnum">{formatMinutes(pagedAt[pagedAt.length - 1] + last.waitMinutes)} in</span>
              </>
            }
          />
        )}
      </ol>
      <PanelFooter>
        <span className="flex items-center gap-2">
          <ShieldCheck className="size-3.5" aria-hidden="true" />
          Acknowledging stops escalation. Escalation notifies people; it never reassigns the incident.
        </span>
      </PanelFooter>
    </Panel>
  )
}

/** One service's escalation levels. Everyone reads them; only Admins change them, and the server checks again. */
export function ServiceEscalation({ service }: { service: Service }) {
  const serviceId = service.id
  const { user } = useAuth()
  const isAdmin = can(user?.role, 'configure')
  const queryClient = useQueryClient()
  const now = useNow(30_000)
  const policy = useQuery({ queryKey: ['escalation-policy', serviceId], queryFn: () => getEscalationPolicy(serviceId) })
  // The fallback contact is set with the schedule.
  const onCall = useQuery({ queryKey: ['on-call', serviceId], queryFn: () => getOnCall(serviceId) })
  // Open incidents nobody has acknowledged are the ones escalating now.
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  // Contacts are users; that list is Admin-only, like editing.
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: isAdmin })

  if (policy.isPending) return <LoadingState message="Loading the escalation levels" />
  if (policy.isError) return <ErrorState message={policy.error.message} onRetry={() => policy.refetch()} />

  const levels = (policy.data?.steps ?? []).toSorted((a, b) => a.stepNumber - b.stepNumber)
  const fallback = onCall.data?.fallbackContactName ?? null
  const escalating = (incidents.data ?? []).filter(
    (incident) => incident.serviceName === service.name && incident.status === 'OPEN' && incident.acknowledgedAt === null,
  )

  return (
    <div className="flex flex-col gap-5">
      {levels.length > 0 &&
        escalating.map((incident) => (
          <div
            key={incident.id}
            className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-sev-2/40 bg-sev-2-bg px-4 py-3"
          >
            <p className="flex min-w-0 items-start gap-3 text-[13px]">
              <BellRing className="mt-0.5 size-4 shrink-0 text-sev-2-fg" aria-hidden="true" />
              <span>
                <strong className="font-medium">Escalating {incident.reference}</strong> · {escalationNow(levels, fallback, incident.createdAt, now)}
              </span>
            </p>
            <Button variant="outline" size="sm" asChild>
              <Link to={`/incidents/${incident.id}`}>Open {incident.reference}</Link>
            </Button>
          </div>
        ))}
      <PagingOrder service={service} levels={levels} fallback={fallback} />
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
