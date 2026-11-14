import { useQuery } from '@tanstack/react-query'
import { listAlerts } from '@/api/alerts'
import { Panel } from '@/components/app/Panel'
import { formatIst } from '@/lib/time'

/** The alerts grouped into this incident (FR-02), from the same list as the alert inbox. */
export function IncidentAlerts({ incidentId }: { incidentId: number }) {
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: listAlerts })
  const mine = alerts.data?.filter((alert) => alert.incidentId === incidentId) ?? []

  return (
    <Panel
      title={
        <>
          Alerts <span className="font-mono text-[12px] text-muted-foreground">{mine.length}</span>
        </>
      }
    >
      {alerts.isPending ? (
        <p className="border-t px-[19px] py-4 text-[13px] text-muted-foreground">Loading alerts…</p>
      ) : alerts.isError ? (
        <p className="border-t px-[19px] py-4 text-[13px] text-muted-foreground">The alerts did not load. The alert inbox lists them too.</p>
      ) : mine.length === 0 ? (
        <p className="border-t px-[19px] py-4 text-[13px] text-muted-foreground">No alert opened or joined this incident; it was declared by hand.</p>
      ) : (
        <ul className="divide-y border-t">
          {mine.map((alert) => (
            <li key={alert.id} className="flex flex-wrap items-center justify-between gap-2 px-[19px] py-3 text-[13px]">
              <span className="flex flex-col gap-0.5">
                <span className="font-medium">{alert.source}</span>
                {alert.correlationKey && <span className="font-mono text-[12px] text-muted-foreground">{alert.correlationKey}</span>}
              </span>
              <span className="font-mono text-[12px] text-muted-foreground tnum">{formatIst(alert.createdAt)}</span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}
