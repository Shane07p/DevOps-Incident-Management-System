import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Sparkles, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { checkDuplicateIncident, type DuplicateIncident } from '@/api/similarIncidents'
import { Button } from '@/components/ui/button'

export function DuplicateIncidentWarning({
  title,
  serviceId,
}: {
  title: string
  serviceId?: string | number
}) {
  // Controlled dismiss state per unique duplicate detected
  const [dismissedId, setDismissedId] = useState<number | null>(null)

  // Debounce the title before querying backend
  const [debouncedTitle, setDebouncedTitle] = useState(title)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      setDebouncedTitle(title)
    }, 400)
    return () => {
      if (timer.current) clearTimeout(timer.current)
    }
  }, [title])

  const { data: duplicate } = useQuery<DuplicateIncident | null>({
    queryKey: ['duplicate-check', debouncedTitle, serviceId],
    queryFn: () => checkDuplicateIncident({ title: debouncedTitle, serviceId }),
    enabled: debouncedTitle.trim().length >= 3,
  })

  // When AI is off, unavailable, no duplicate found, or user dismissed this incident
  if (!duplicate || duplicate.id === dismissedId) {
    return null
  }

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="duplicate-warning"
      className="flex items-start justify-between gap-3 rounded-lg border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-foreground transition-all"
    >
      <div className="flex items-start gap-2.5">
        <AlertTriangle className="size-4 shrink-0 text-amber-600 mt-0.5" aria-hidden="true" />
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-amber-900 dark:text-amber-200">
              Likely open duplicate
            </span>
            <span
              data-testid="ai-label"
              className="inline-flex items-center gap-1 rounded bg-amber-500/20 px-1.5 py-0.5 text-[10.5px] font-medium text-amber-900 dark:text-amber-200"
            >
              <Sparkles className="size-3 text-amber-500" aria-hidden="true" />
              AI
            </span>
          </div>
          <p className="text-foreground/90">
            This looks like open incident{' '}
            <Link
              to={`/incidents/${duplicate.id}`}
              target="_blank"
              rel="noreferrer"
              className="font-mono font-semibold underline hover:text-primary"
            >
              {duplicate.reference}
            </Link>
            {duplicate.title ? ` ("${duplicate.title}")` : ''}.
          </p>
          <span className="text-xs text-muted-foreground">
            Declaration is not blocked. You can still submit if this is a separate incident.
          </span>
        </div>
      </div>

      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-label="Dismiss duplicate warning"
        onClick={() => setDismissedId(duplicate.id)}
        className="size-7 p-0 text-muted-foreground hover:text-foreground"
      >
        <X className="size-3.5" aria-hidden="true" />
      </Button>
    </div>
  )
}

