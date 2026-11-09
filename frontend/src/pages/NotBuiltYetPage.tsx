import { Hammer } from 'lucide-react'
import { useLocation } from 'react-router-dom'
import { findPage } from '@/app/navigation'

// When each screen lands, from docs/sprint-plan.md.
const PLANNED_SPRINT: Record<string, number> = {
  '/incidents': 1,
  '/services': 1,
  '/users': 1,
  '/alerts': 2,
  '/schedules': 2,
  '/monitors': 2,
  '/heartbeats': 2,
  '/escalation': 3,
  '/service-map': 4,
  '/status-pages': 4,
  '/logs': 4,
  '/metrics': 4,
  '/follow-ups': 5,
  '/insights': 5,
}

/** Stands in for a screen that hasn't been built yet, and says when it will be. */
export function NotBuiltYetPage() {
  const { pathname } = useLocation()
  const page = findPage(pathname)
  const sprint = page ? PLANNED_SPRINT[page.path] : undefined

  return (
    <section className="mx-auto flex max-w-xl flex-col items-center gap-4 py-24 text-center">
      <span className="grid size-12 place-items-center rounded-full bg-muted text-muted-foreground">
        <Hammer className="size-5" aria-hidden="true" />
      </span>
      <h1 className="text-[44px] leading-[0.95] font-[750]" style={{ fontStretch: '62%' }}>
        {page?.label ?? 'Not built yet'}
      </h1>
      {page && <p className="text-[15px] text-muted-foreground">{page.description}.</p>}
      <p className="text-sm text-muted-foreground">
        {sprint ? `This screen is planned for Sprint ${sprint}.` : 'This screen is not built yet.'}
      </p>
    </section>
  )
}
