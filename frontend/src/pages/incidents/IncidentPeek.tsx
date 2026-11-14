import { useQuery } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { getIncident, type IncidentSummary } from '@/api/incidents'
import { Avatar } from '@/components/app/Avatar'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { SlaBadge } from '@/components/app/SlaClock'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { formatIst } from '@/lib/time'
import { useNow } from '@/lib/useNow'

/**
 * A quick look at one incident from the list or the board, without leaving it: both clocks, the Lead, what is
 * happening, and the latest timeline entries. Changes happen in the incident workspace, not here.
 */
export function IncidentPeek({ incident, onClose }: { incident: IncidentSummary | null; onClose: () => void }) {
  const now = useNow()
  const detail = useQuery({
    queryKey: ['incident', incident?.id],
    queryFn: () => getIncident(incident!.id),
    enabled: incident !== null,
  })

  return (
    <Sheet open={incident !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent side="right" className="w-[420px] max-w-[100vw] gap-0 overflow-y-auto p-0 sm:max-w-[420px]">
        {incident && (
          <>
            <SheetHeader className="gap-2 border-b p-5 pr-12">
              <span className="flex items-center gap-2 font-mono text-[12px] text-muted-foreground">
                {incident.reference} · {incident.serviceName}
              </span>
              <SheetTitle className="text-[20px] leading-tight font-medium tracking-[-0.02em]">{incident.title}</SheetTitle>
              <SheetDescription className="flex flex-wrap items-center gap-3">
                <SeverityBadge severity={incident.severity} />
                <StatusBadge status={incident.status} />
              </SheetDescription>
            </SheetHeader>

            <dl className="grid gap-3 border-b p-5 text-[13px]">
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted-foreground">Lead</dt>
                <dd className="flex items-center gap-2">
                  {incident.assignee ? (
                    <>
                      <Avatar name={incident.assignee.displayName} size="sm" />
                      {incident.assignee.displayName}
                    </>
                  ) : (
                    <span className="text-muted-foreground">Nobody yet</span>
                  )}
                </dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted-foreground">Response</dt>
                <dd>
                  <SlaBadge name="Response" dueAt={incident.responseDueAt} stoppedAt={incident.acknowledgedAt} now={now} />
                </dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted-foreground">Resolution</dt>
                <dd>
                  <SlaBadge name="Resolution" dueAt={incident.resolutionDueAt} stoppedAt={incident.resolvedAt} now={now} />
                </dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted-foreground">Declared</dt>
                <dd className="font-mono text-[12px] tnum">{formatIst(incident.createdAt)}</dd>
              </div>
            </dl>

            <div className="flex flex-col gap-4 p-5 text-[13px]">
              {detail.isPending ? (
                <p className="text-muted-foreground">Loading…</p>
              ) : detail.isError ? (
                <p className="text-muted-foreground">The details did not load. Open the incident to see them.</p>
              ) : (
                <>
                  <p className="whitespace-pre-line">{detail.data.description}</p>
                  {detail.data.timeline.length > 0 && (
                    <section aria-label="Latest on the timeline" className="flex flex-col gap-2">
                      <h3 className="text-[12px] font-medium text-muted-foreground">Latest on the timeline</h3>
                      <ol className="flex flex-col gap-2">
                        {detail.data.timeline.slice(-3).map((entry) => (
                          <li key={entry.id} className="flex flex-col gap-0.5 rounded-md border px-3 py-2">
                            <span className="font-mono text-[11px] text-muted-foreground tnum">{formatIst(entry.recordedAt)}</span>
                            <span>{entry.details ?? entry.eventType}</span>
                          </li>
                        ))}
                      </ol>
                    </section>
                  )}
                </>
              )}
              <Button asChild className="self-start">
                <Link to={`/incidents/${incident.id}`}>
                  Open incident
                  <ArrowRight aria-hidden="true" />
                </Link>
              </Button>
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  )
}
