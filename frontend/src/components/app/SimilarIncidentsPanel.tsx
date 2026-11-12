import { useQuery } from '@tanstack/react-query'
import { Sparkles, ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { fetchSimilarIncidents, type SimilarIncident } from '@/api/similarIncidents'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'

function formatMatchPercent(score: number): string {
  return `${Math.round(score * 100)}% match`
}

export function SimilarIncidentsPanel({ incidentId }: { incidentId: number }) {
  const { data: similar = [], isError } = useQuery({
    queryKey: ['incident', incidentId, 'similar'],
    queryFn: () => fetchSimilarIncidents(incidentId),
    enabled: Boolean(incidentId && incidentId > 0),
  })

  // Hide on error (API down, wrong path, 401, etc.) — don't crash the detail page
  if (isError) return null

  // When AI is off, unavailable, or returns no matches, hide quietly (FR-18.4)
  if (!similar || similar.length === 0) {
    return null
  }

  // Display at most 3 similar past incidents
  const displayed = similar.slice(0, 3)

  return (
    <section aria-labelledby="similar-incidents-heading" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Sparkles className="size-4 text-accent-foreground" aria-hidden="true" />
          <h2 id="similar-incidents-heading" className="text-lg font-semibold">
            Similar past incidents
          </h2>
        </div>
        <span
          data-testid="ai-label"
          className="inline-flex items-center gap-1 rounded-full border border-ring bg-accent px-2 py-0.5 text-[11px] font-medium text-accent-foreground"
        >
          <Sparkles className="size-3" aria-hidden="true" />
          AI-generated suggestion
        </span>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        {displayed.map((item: SimilarIncident) => {
          return (
            <div
              key={item.id}
              data-testid="similar-incident-card"
              className="flex flex-col justify-between gap-3 rounded-lg border bg-card p-4 transition-colors hover:border-foreground/20"
            >
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-xs text-muted-foreground">{item.reference}</span>
                  <span
                    data-testid="match-score"
                    className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs font-semibold text-foreground tnum"
                  >
                    {formatMatchPercent(item.score)}
                  </span>
                </div>

                <Link
                  to={`/incidents/${item.id}`}
                  className="font-medium text-foreground hover:underline line-clamp-2"
                >
                  {item.title}
                </Link>

                <div className="flex flex-wrap gap-1.5">
                  {item.severity && <SeverityBadge severity={item.severity} />}
                  {item.status && <StatusBadge status={item.status} />}
                </div>

                {item.fix && (
                  <div data-testid="similar-incident-fix" className="mt-1 flex flex-col gap-0.5 border-t pt-2 text-xs">
                    <span className="font-medium text-muted-foreground">Fix:</span>
                    <p className="text-foreground/90 line-clamp-3">{item.fix}</p>
                  </div>
                )}
              </div>

              <Link
                to={`/incidents/${item.id}`}
                className="mt-1 inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
              >
                View incident
                <ArrowRight className="size-3" aria-hidden="true" />
              </Link>
            </div>
          )
        })}
      </div>
    </section>
  )
}
