import { cn } from '@/lib/utils'

export type HealthTone = 'ok' | 'warn' | 'fail' | 'neutral'

const TONES: Record<HealthTone, { dot: string; text: string }> = {
  ok: { dot: 'bg-health-operational', text: 'text-health-operational-fg' },
  warn: { dot: 'bg-health-degraded', text: 'text-health-degraded-fg' },
  fail: { dot: 'bg-health-down', text: 'text-health-down-fg' },
  neutral: { dot: 'bg-muted-foreground', text: 'text-muted-foreground' },
}

/** A state with its colour dot and words, so the colour is never the only signal. */
export function Health({ tone, children, className }: { tone: HealthTone; children: React.ReactNode; className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2 text-sm font-medium', TONES[tone].text, className)}>
      <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', TONES[tone].dot)} />
      {children}
    </span>
  )
}
