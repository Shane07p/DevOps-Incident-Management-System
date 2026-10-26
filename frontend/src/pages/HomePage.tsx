import { Plus } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { useIstTime } from '@/components/app/IstClock'
import { Button } from '@/components/ui/button'

function greeting(hour: number) {
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

const dateFormat = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  weekday: 'long',
  day: 'numeric',
  month: 'long',
})

export function HomePage() {
  const { user } = useAuth()
  const { now, hours, hhmm } = useIstTime()
  const firstName = user?.displayName.split(' ')[0] ?? ''

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <span className="font-mono text-[12.5px] text-muted-foreground tnum">
            {dateFormat.format(now)} · {hhmm} IST
          </span>
          <h1 className="text-[52px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            {greeting(hours)}, {firstName}
          </h1>
        </div>
        {can(user?.role, 'respond') && (
          <Button asChild>
            <Link to="/incidents/new">
              <Plus aria-hidden="true" />
              Declare incident
            </Link>
          </Button>
        )}
      </div>
      <div className="rounded-lg border bg-card p-8 text-muted-foreground">
        Your incidents, the alerts routed to you, and your on-call shift will appear here once the incident
        screens are built (Sprint 1).
      </div>
    </div>
  )
}
