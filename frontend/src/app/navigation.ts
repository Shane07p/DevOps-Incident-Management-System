import {
  Boxes,
  CalendarClock,
  ChartColumn,
  House,
  Radar,
  Siren,
  type LucideIcon,
} from 'lucide-react'

export interface NavPage {
  path: string
  label: string
  description: string
}

export interface NavArea {
  id: string
  label: string
  icon: LucideIcon
  pages: NavPage[]
}

/** The six areas of the dock, in the order of the Settle design. */
export const AREAS: NavArea[] = [
  { id: 'home', label: 'Home', icon: House, pages: [{ path: '/home', label: 'Home', description: 'Your incidents, alerts, and shift' }] },
  {
    id: 'response',
    label: 'Response',
    icon: Siren,
    pages: [
      { path: '/incidents', label: 'Incidents', description: 'Active and past incidents' },
      { path: '/alerts', label: 'Alerts', description: 'Grouped alerts waiting for triage' },
      { path: '/follow-ups', label: 'Follow-ups', description: 'Actions from every incident' },
    ],
  },
  {
    id: 'oncall',
    label: 'On-call',
    icon: CalendarClock,
    pages: [
      { path: '/schedules', label: 'Schedules', description: 'Who is on call now and next' },
      { path: '/escalation', label: 'Escalation policies', description: 'Who gets paged next, and when' },
    ],
  },
  {
    id: 'catalog',
    label: 'Catalog',
    icon: Boxes,
    pages: [
      { path: '/services', label: 'Services', description: 'Owners, health, and on-call' },
      { path: '/service-map', label: 'Service map', description: 'Dependencies and blast radius' },
      { path: '/runbooks', label: 'Runbooks', description: 'Step-by-step fixes, linked to services' },
    ],
  },
  {
    id: 'monitoring',
    label: 'Monitoring',
    icon: Radar,
    pages: [
      { path: '/monitors', label: 'Monitors', description: 'Uptime checks for URLs, hosts, and certificates' },
      { path: '/heartbeats', label: 'Heartbeats', description: 'Scheduled jobs that must check in on time' },
      { path: '/status-pages', label: 'Status pages', description: 'What customers see during an incident' },
    ],
  },
  {
    id: 'telemetry',
    label: 'Telemetry',
    icon: ChartColumn,
    pages: [
      { path: '/logs', label: 'Logs', description: 'Search and live tail across services' },
      { path: '/metrics', label: 'Metrics', description: 'Traffic, errors, latency, and resources' },
      { path: '/insights', label: 'Insights', description: 'Response and resolution trends' },
    ],
  },
]

export const ADMIN_PAGES: NavPage[] = [
  { path: '/users', label: 'Users', description: 'Accounts and roles' },
  { path: '/tools', label: 'Tools', description: 'Monitoring tools and their tokens' },
]

export function findArea(pathname: string) {
  return AREAS.find((area) => area.pages.some((page) => pathname === page.path || pathname.startsWith(`${page.path}/`)))
}

export function findPage(pathname: string) {
  return [...AREAS.flatMap((area) => area.pages), ...ADMIN_PAGES].find(
    (page) => pathname === page.path || pathname.startsWith(`${page.path}/`),
  )
}
