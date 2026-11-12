import type { ReactNode } from 'react'
import { createBrowserRouter, type RouteObject } from 'react-router-dom'
import { AREAS, ADMIN_PAGES } from '@/app/navigation'
import { RequireAuth } from '@/auth/RequireAuth'
import { AppShell } from '@/components/shell/AppShell'
import { AccessTokensPage } from '@/pages/AccessTokensPage'
import { AlertsPage } from '@/pages/AlertsPage'
import { AuditLogPage } from '@/pages/AuditLogPage'
import { EscalationPage } from '@/pages/EscalationPage'
import { HistorySearchPage } from '@/pages/HistorySearchPage'
import { HomePage } from '@/pages/HomePage'
import { IncidentDetailPage } from '@/pages/IncidentDetailPage'
import { IncidentsPage } from '@/pages/IncidentsPage'
import { LoginPage } from '@/pages/LoginPage'
import { LogsPage } from '@/pages/LogsPage'
import { SignupPage } from '@/pages/SignupPage'
import { NewIncidentPage } from '@/pages/NewIncidentPage'
import { NotBuiltYetPage } from '@/pages/NotBuiltYetPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { SchedulesPage } from '@/pages/SchedulesPage'
import { ServiceMapPage } from '@/pages/ServiceMapPage'
import { ServicesPage } from '@/pages/ServicesPage'
import { RunbooksPage } from '@/pages/RunbooksPage'
import { ToolsPage } from '@/pages/ToolsPage'
import { UsersPage } from '@/pages/UsersPage'

// Dock screens that exist so far, open to every role; each page hides what a role can't use.
const SCREENS: Record<string, ReactNode> = {
  '/escalation': <EscalationPage />,
  '/logs': <LogsPage />,
  '/schedules': <SchedulesPage />,
  '/service-map': <ServiceMapPage />,
  '/services': <ServicesPage />,
  '/runbooks': <RunbooksPage />,
  '/tokens': <AccessTokensPage />,
}

// Admin screens that exist so far; the rest of ADMIN_PAGES show when they are planned.
const ADMIN_SCREENS: Record<string, ReactNode> = { '/audit': <AuditLogPage />, '/tools': <ToolsPage />, '/users': <UsersPage /> }

// Response screens: incidents and alerts. Open to every role; they rank above the /incidents/* and /alerts/*
// placeholders below. Declaring is for responders (the roles `can(role, 'respond')` allows); others are sent home.
const RESPONSE_SCREENS: Record<string, ReactNode> = {
  '/alerts': <AlertsPage />,
  '/history': <HistorySearchPage />,
  '/incidents': <IncidentsPage />,
  '/incidents/:id': <IncidentDetailPage />,
}
const RESPONDER_SCREENS: Record<string, ReactNode> = { '/incidents/new': <NewIncidentPage /> }

// Screens not built yet show when they are planned; each is replaced by its real page as it lands.
const placeholderPaths = AREAS.flatMap((area) => area.pages.map((page) => page.path)).filter(
  (path) => path !== '/home' && !(path in SCREENS),
)

// Exported so tests can run the real route table, guards included.
export const routes: RouteObject[] = [
  // Public pages load on demand, so logged-in users never download the landing animations.
  { path: '/', lazy: () => import('@/pages/LandingPage').then((m) => ({ Component: m.LandingPage })) },
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
  // Each org's public page (D45); without a slug the page says how to find one.
  { path: '/status', lazy: () => import('@/pages/StatusPage').then((m) => ({ Component: m.StatusPage })) },
  { path: '/status/:slug', lazy: () => import('@/pages/StatusPage').then((m) => ({ Component: m.StatusPage })) },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/home', element: <HomePage /> },
          ...Object.entries(SCREENS).map(([path, element]) => ({ path, element })),
          ...placeholderPaths.map((path) => ({ path: `${path}/*`, element: <NotBuiltYetPage /> })),
          ...Object.entries(RESPONSE_SCREENS).map(([path, element]) => ({ path, element })),
          {
            element: <RequireAuth roles={['ADMIN', 'ENGINEER']} />,
            children: Object.entries(RESPONDER_SCREENS).map(([path, element]) => ({ path, element })),
          },
          {
            element: <RequireAuth roles={['ADMIN']} />,
            children: ADMIN_PAGES.map((page) => ({ path: page.path, element: ADMIN_SCREENS[page.path] ?? <NotBuiltYetPage /> })),
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(routes)
