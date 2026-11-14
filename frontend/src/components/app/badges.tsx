import { Activity, Check, CheckCircle2, Crosshair, Search, type LucideIcon } from 'lucide-react'
import type { Role } from '@/api/schemas'
import { ROLE_LABEL } from '@/auth/roles'
import { cn } from '@/lib/utils'

export type Severity = 'SEV1' | 'SEV2' | 'SEV3' | 'SEV4'
export type IncidentStatus =
  | 'OPEN'
  | 'ACKNOWLEDGED'
  | 'INVESTIGATING'
  | 'IDENTIFIED'
  | 'MONITORING'
  | 'RESOLVED'
  | 'CLOSED'
export type Criticality = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export const LIFECYCLE: IncidentStatus[] = [
  'OPEN',
  'ACKNOWLEDGED',
  'INVESTIGATING',
  'IDENTIFIED',
  'MONITORING',
  'RESOLVED',
  'CLOSED',
]

// Only SEV1 (red, boxed) and SEV2 (amber) are coloured; SEV3 and SEV4 stay neutral (index.css).
const SEVERITY_STYLE: Record<Severity, string> = {
  SEV1: 'rounded-[6px] bg-sev-1-bg pr-[7px] pl-[6px] text-sev-1-fg shadow-[inset_0_0_0_1px_var(--sev-1-line)]',
  SEV2: 'text-sev-2-fg',
  SEV3: 'text-sev-3-fg',
  SEV4: 'text-sev-4-fg',
}

/** Signal bars and the severity as text: SEV1 fills all four bars, SEV4 fills one. */
export function SeverityBadge({ severity, className }: { severity: Severity; className?: string }) {
  const filled = 5 - Number(severity.slice(3))
  return (
    <span
      className={cn(
        'inline-flex h-[22px] shrink-0 items-center gap-[5px] text-[12px] leading-none font-semibold whitespace-nowrap',
        SEVERITY_STYLE[severity],
        className,
      )}
    >
      <svg width="11" height="10" viewBox="0 0 11 10" aria-hidden="true">
        {[0, 1, 2, 3].map((bar) => (
          <rect
            key={bar}
            x={bar * 3}
            y={7 - bar * 2 - (bar === 3 ? 1 : 0)}
            width="2"
            height={3 + bar * 2 + (bar === 3 ? 1 : 0)}
            rx="0.5"
            fill="currentColor"
            opacity={bar < filled ? 1 : 0.25}
          />
        ))}
      </svg>
      {severity}
    </span>
  )
}

/** "INVESTIGATING" → "Investigating". */
const statusLabel = (status: IncidentStatus) => status.charAt(0) + status.slice(1).toLowerCase()

const STATUS_ICON: Record<Exclude<IncidentStatus, 'OPEN'>, LucideIcon> = {
  ACKNOWLEDGED: Check,
  INVESTIGATING: Search,
  IDENTIFIED: Crosshair,
  MONITORING: Activity,
  RESOLVED: CheckCircle2,
  CLOSED: CheckCircle2,
}

/** An icon and a word. Only a finished incident (resolved or closed) is coloured, in green. */
export function StatusBadge({ status, className }: { status: IncidentStatus; className?: string }) {
  const finished = status === 'RESOLVED' || status === 'CLOSED'
  const Icon = status === 'OPEN' ? null : STATUS_ICON[status]
  return (
    <span
      className={cn(
        'inline-flex h-[22px] shrink-0 items-center gap-[5px] text-[12px] leading-none whitespace-nowrap',
        finished ? 'text-status-resolved' : 'text-foreground',
        className,
      )}
    >
      {Icon ? (
        <Icon className={cn('size-[13px]', !finished && 'text-status-open')} aria-hidden="true" />
      ) : (
        <span className="mx-[3px] size-[7px] rounded-full bg-status-open" aria-hidden="true" />
      )}
      {statusLabel(status)}
    </span>
  )
}

const CRITICALITY_STYLE: Record<Criticality, string> = {
  LOW: 'bg-criticality-low-bg text-criticality-low',
  MEDIUM: 'bg-criticality-medium-bg text-criticality-medium',
  HIGH: 'bg-criticality-high-bg text-criticality-high',
  CRITICAL: 'bg-criticality-critical-bg text-criticality-critical-fg',
}

export function CriticalityBadge({ criticality, className }: { criticality: Criticality; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex h-[22px] items-center rounded-xs px-[7px] text-[11.5px] leading-none font-semibold capitalize',
        CRITICALITY_STYLE[criticality],
        className,
      )}
    >
      {criticality.toLowerCase()}
    </span>
  )
}

export function RoleBadge({ role, className }: { role: Role; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex h-[22px] items-center rounded-full border px-2 text-[11.5px] leading-none font-semibold text-muted-foreground',
        role === 'ADMIN' && 'border-transparent bg-primary text-primary-foreground',
        className,
      )}
    >
      {ROLE_LABEL[role]}
    </span>
  )
}
