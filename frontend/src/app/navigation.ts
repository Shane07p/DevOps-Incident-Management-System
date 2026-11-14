import {
  Activity,
  BookOpen,
  Box,
  Boxes,
  CalendarClock,
  CalendarDays,
  ChartColumn,
  CheckCheck,
  Flame,
  Gauge,
  GitBranch,
  Globe,
  HeartPulse,
  History,
  KeyRound,
  Layers,
  LayoutDashboard,
  Network,
  Plug,
  Radar,
  Radio,
  Shield,
  Siren,
  Terminal,
  Users,
  Webhook,
  type LucideIcon,
} from 'lucide-react'

export interface NavPage {
  path: string
  label: string
  description: string
  icon: LucideIcon
}

export interface NavArea {
  id: string
  label: string
  icon: LucideIcon
  pages: NavPage[]
  /** Shown in the sidebar as a group that opens and closes; it starts open when it holds the current page. */
  collapsible?: boolean
}

/** The six areas of the sidebar, in the order of the Settle design. */
export const AREAS: NavArea[] = [
  {
    id: 'home',
    label: 'Overview',
    icon: LayoutDashboard,
    pages: [{ path: '/home', label: 'Overview', description: 'Your incidents, alerts, and shift', icon: LayoutDashboard }],
  },
  {
    id: 'response',
    label: 'Response',
    icon: Siren,
    pages: [
      { path: '/incidents', label: 'Incidents', description: 'Active and past incidents', icon: Flame },
      { path: '/alerts', label: 'Alerts', description: 'Grouped alerts waiting for triage', icon: Radio },
      { path: '/history', label: 'History', description: 'Search historical incidents by keyword, service, severity, or status', icon: History },
      { path: '/follow-ups', label: 'Follow-ups', description: 'Actions from every incident', icon: CheckCheck },
    ],
  },
  {
    id: 'oncall',
    label: 'On-call',
    icon: CalendarClock,
    pages: [
      { path: '/schedules', label: 'Schedules', description: 'Who is on call now and next', icon: CalendarDays },
      { path: '/escalation', label: 'Escalation policies', description: 'Who gets paged next, and when', icon: Layers },
    ],
  },
  {
    id: 'catalog',
    label: 'Catalog',
    icon: Boxes,
    pages: [
      { path: '/services', label: 'Services', description: 'Owners, health, and on-call', icon: Box },
      { path: '/service-map', label: 'Service map', description: 'Dependencies and blast radius', icon: Network },
      { path: '/runbooks', label: 'Runbooks', description: 'Step-by-step fixes, linked to services', icon: BookOpen },
    ],
  },
  {
    id: 'monitoring',
    label: 'Monitoring',
    icon: Radar,
    collapsible: true,
    pages: [
      { path: '/monitors', label: 'Monitors', description: 'Uptime checks for URLs, hosts, and certificates', icon: Activity },
      { path: '/heartbeats', label: 'Heartbeats', description: 'Scheduled jobs that must check in on time', icon: HeartPulse },
      { path: '/status-pages', label: 'Status pages', description: 'What customers see during an incident', icon: Globe },
    ],
  },
  {
    id: 'telemetry',
    label: 'Telemetry',
    icon: ChartColumn,
    collapsible: true,
    pages: [
      { path: '/logs', label: 'Logs', description: 'Search and live tail across services', icon: Terminal },
      { path: '/metrics', label: 'Metrics', description: 'Traffic, errors, latency, and resources', icon: Gauge },
      { path: '/insights', label: 'Insights', description: 'Response and resolution trends', icon: GitBranch },
    ],
  },
]

export const ADMIN_PAGES: NavPage[] = [
  { path: '/users', label: 'Members', description: 'People in your org and their roles', icon: Users },
  { path: '/tools', label: 'Tools', description: 'Monitoring tools and their tokens', icon: Webhook },
  { path: '/audit', label: 'Audit log', description: 'Every configuration change, newest first', icon: Shield },
  { path: '/integrations', label: 'Integrations', description: 'Connect your Grafana for logs and metrics', icon: Plug },
]

export const ACCOUNT_PAGES: NavPage[] = [
  { path: '/tokens', label: 'Access tokens', description: 'Personal access tokens for AI tools and scripts', icon: KeyRound },
]

export function findArea(pathname: string) {
  return AREAS.find((area) => area.pages.some((page) => pathname === page.path || pathname.startsWith(`${page.path}/`)))
}

export function findPage(pathname: string) {
  return [...AREAS.flatMap((area) => area.pages), ...ADMIN_PAGES, ...ACCOUNT_PAGES].find(
    (page) => pathname === page.path || pathname.startsWith(`${page.path}/`),
  )
}
