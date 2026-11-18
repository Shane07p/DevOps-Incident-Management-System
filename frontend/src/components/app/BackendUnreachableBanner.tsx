import { AlertTriangle } from 'lucide-react'
import { cn } from '@/lib/utils'

export function BackendUnreachableBanner({ className }: { className?: string }) {
  return (
    <div
      role="alert"
      data-testid="backend-unreachable-banner"
      className={cn(
        'mb-4 flex items-center gap-2 rounded-md border border-destructive/20 bg-destructive/10 px-4 py-3 text-sm text-destructive',
        className,
      )}
    >
      <AlertTriangle className="size-4 shrink-0" aria-hidden="true" />
      <span>The backend cannot be reached. Retrying connection...</span>
    </div>
  )
}
