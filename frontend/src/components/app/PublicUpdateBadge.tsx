import { PUBLIC_UPDATE_LABEL, type PublicUpdateStatus } from '@/api/publicStatus'
import { cn } from '@/lib/utils'

const COLOR: Record<PublicUpdateStatus, string> = {
  INVESTIGATING: 'text-status-investigating',
  IDENTIFIED: 'text-status-identified',
  MONITORING: 'text-status-monitoring',
  RESOLVED: 'text-status-resolved',
}

export function PublicUpdateBadge({ status }: { status: PublicUpdateStatus }) {
  return <span className={cn('text-[13px] font-medium', COLOR[status])}>{PUBLIC_UPDATE_LABEL[status]}</span>
}
