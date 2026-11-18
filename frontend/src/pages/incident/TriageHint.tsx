import { useQuery } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { fetchTriageHint, type TriageHint as TriageHintData } from '@/api/triageHint'
import { type Severity } from '@/api/schemas'
import { SeverityBadge } from '@/components/app/badges'
import { Button } from '@/components/ui/button'

/** Minimum title length before firing the AI call. */
const MIN_TITLE_LENGTH = 3

/** Debounce delay in milliseconds. */
const DEBOUNCE_MS = 500

interface TriageHintProps {
  /** Current title field value from the form. */
  title: string
  /** Current description field value from the form. */
  description: string
  /**
   * Called when the user clicks Apply. The parent should update the form
   * fields using React Hook Form's setValue without submitting.
   */
  onApply: (severity: Severity, serviceId: number | null) => void
}

/**
 * AI triage hint banner for the declare incident form (AI-4, Issue #247).
 *
 * - Shows suggested severity, service, and reason.
 * - Apply button updates the form — user must click Apply explicitly.
 * - Hidden when AI is unavailable, title is too short, or hint is dismissed.
 * - Never auto-fills. Never blocks declaration.
 */
export function TriageHint({ title, description, onApply }: TriageHintProps) {
  const [debouncedTitle, setDebouncedTitle] = useState(title)
  const [debouncedDesc, setDebouncedDesc] = useState(description)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Dismiss state: once applied or dismissed, hide until inputs change
  const [appliedKey, setAppliedKey] = useState<string | null>(null)

  useEffect(() => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      setDebouncedTitle(title)
      setDebouncedDesc(description)
    }, DEBOUNCE_MS)
    return () => {
      if (timer.current) clearTimeout(timer.current)
    }
  }, [title, description])

  const queryKey = ['triage-hint', debouncedTitle, debouncedDesc]
  const currentKey = queryKey.join('|')

  const { data: hint } = useQuery<TriageHintData | null>({
    queryKey,
    queryFn: () => fetchTriageHint(debouncedTitle, debouncedDesc),
    enabled: debouncedTitle.trim().length >= MIN_TITLE_LENGTH,
    retry: false,
    // Keep previous data while re-fetching to avoid flicker
    placeholderData: null,
  })

  // Hide if applied/dismissed for this exact query
  if (appliedKey === currentKey) return null

  // Hide when AI is off, unavailable, or hint is null/empty
  if (!hint || !hint.severity) return null

  const handleApply = () => {
    const serviceId = hint.service?.id ?? null
    onApply(hint.severity, serviceId)
    setAppliedKey(currentKey)
  }

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="triage-hint"
      className="flex flex-col gap-2 rounded-lg border border-primary/20 bg-primary/5 p-4 text-sm text-foreground transition-all"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-1.5">
          {/* AI label + severity */}
          <div className="flex items-center gap-2">
            <span
              data-testid="ai-label"
              className="inline-flex items-center gap-1 rounded bg-primary/15 px-1.5 py-0.5 text-[10.5px] font-medium text-primary"
            >
              <Sparkles className="size-3" aria-hidden="true" />
              AI
            </span>
            <span className="text-[12px] font-medium text-muted-foreground">Suggested</span>
            <SeverityBadge severity={hint.severity} />
            {hint.service?.name && (
              <>
                <span className="text-muted-foreground">·</span>
                <span data-testid="triage-hint-service" className="font-medium">
                  {hint.service.name}
                </span>
              </>
            )}
          </div>

          {/* Reason */}
          {hint.reason && (
            <p data-testid="triage-hint-reason" className="text-[12px] text-muted-foreground leading-snug">
              {hint.reason}
            </p>
          )}
        </div>

        {/* Apply button */}
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={handleApply}
          data-testid="triage-hint-apply"
          className="shrink-0 text-xs"
        >
          Apply
        </Button>
      </div>
    </div>
  )
}
