import { useQuery } from '@tanstack/react-query'
import { TriangleAlert } from 'lucide-react'
import { listHeartbeats } from '@/api/heartbeats'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { formatIst } from '@/lib/time'
import { heartbeatStatus } from './heartbeats/heartbeatStatus'

function every(seconds: number) {
  if (seconds === 86400) return 'Every day'
  if (seconds % 86400 === 0) return `Every ${seconds / 86400} days`
  if (seconds % 3600 === 0) return `Every ${seconds / 3600} h`
  if (seconds % 60 === 0) return `Every ${seconds / 60} min`
  return `Every ${seconds} s`
}

function duration(seconds: number) {
  return seconds % 60 === 0 ? `${seconds / 60} min` : `${seconds} s`
}

/** Scheduled jobs that must check in on time (FR-24). Every role may read them. */
export function HeartbeatsPage() {
  const heartbeats = useQuery({ queryKey: ['heartbeats'], queryFn: listHeartbeats })
  // Judged at the moment the list was fetched, so a render never reads the clock.
  const now = heartbeats.dataUpdatedAt
  const list = heartbeats.data ?? []
  const missed = list.filter((heartbeat) => heartbeatStatus(heartbeat, now).label === 'Missed')

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
          Heartbeats
        </h1>
        <p className="text-muted-foreground">Scheduled jobs that must check in on time.</p>
      </div>

      {missed.length > 0 && (
        <div role="status" className="flex items-start gap-3 rounded-lg border border-health-down/40 bg-sev-1-bg p-4 text-sm">
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-health-down-fg" />
          <p>
            {missed.map((heartbeat, index) => (
              <span key={heartbeat.id}>
                {index > 0 && '; '}
                <strong>{heartbeat.name}</strong>
                {heartbeat.lastPingAt ? ` last checked in at ${formatIst(heartbeat.lastPingAt)}` : ' has never checked in'}
              </span>
            ))}
            . Past the grace period, so an alert is raised.
          </p>
        </div>
      )}

      {heartbeats.isPending ? (
        <LoadingState message="Loading heartbeats" />
      ) : heartbeats.isError ? (
        <ErrorState message={heartbeats.error.message} onRetry={() => heartbeats.refetch()} />
      ) : list.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border p-8 text-center text-muted-foreground">No heartbeats yet.</p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full min-w-[720px] text-left text-sm">
            <thead className="border-b border-border text-muted-foreground">
              <tr>
                <th scope="col" className="px-4 py-3 font-medium">Job</th>
                <th scope="col" className="px-4 py-3 font-medium">Service</th>
                <th scope="col" className="px-4 py-3 font-medium">Status</th>
                <th scope="col" className="px-4 py-3 font-medium">Expected</th>
                <th scope="col" className="px-4 py-3 font-medium">Last ping</th>
                <th scope="col" className="px-4 py-3 font-medium">Next expected</th>
                <th scope="col" className="px-4 py-3 font-medium">Grace</th>
              </tr>
            </thead>
            <tbody>
              {list.map((heartbeat) => {
                const status = heartbeatStatus(heartbeat, now)
                return (
                  <tr key={heartbeat.id} className="border-b border-border last:border-0">
                    <td className="px-4 py-3 font-medium">{heartbeat.name}</td>
                    <td className="px-4 py-3">{heartbeat.serviceName}</td>
                    <td className="px-4 py-3"><Health tone={status.tone}>{status.label}</Health></td>
                    <td className="px-4 py-3">{every(heartbeat.intervalSeconds)}</td>
                    <td className="px-4 py-3 font-mono">{heartbeat.lastPingAt ? formatIst(heartbeat.lastPingAt) : 'Never'}</td>
                    <td className="px-4 py-3 font-mono">{heartbeat.nextDueAt ? formatIst(heartbeat.nextDueAt) : '—'}</td>
                    <td className="px-4 py-3">{duration(heartbeat.graceSeconds)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <div className="flex flex-wrap justify-between gap-2 border-t border-border px-4 py-3 text-xs text-muted-foreground">
            <span>Late means inside the grace period; missed means past it, which raises an alert.</span>
            <span>All times in IST</span>
          </div>
        </div>
      )}
    </div>
  )
}
