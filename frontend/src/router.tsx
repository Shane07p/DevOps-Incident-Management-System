
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
import { IntegrationsPage } from '@/pages/IntegrationsPage'
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
import { MonitorsPage } from '@/pages/MonitorsPage'
import { HeartbeatsPage } from '@/pages/HeartbeatsPage'

// Screens available to every authenticated role.
const SCREENS: Record<string, ReactNode> = {
  '/escalation': <EscalationPage />,
  '/logs': <LogsPage />,
  '/schedules': <SchedulesPage />,
  '/service-map': <ServiceMapPage />,
  '/services': <ServicesPage />,
  '/runbooks': <RunbooksPage />,
  '/tokens': <AccessTokensPage />,
  '/monitors': <MonitorsPage />,
  '/heartbeats': <HeartbeatsPage />,
}

// Admin screens.
const ADMIN_SCREENS: Record<string, ReactNode> = {
  '/audit': <AuditLogPage />,
  '/integrations': <IntegrationsPage />,
  '/tools': <ToolsPage />,
  '/users': <UsersPage />,
}

// Response screens.
const RESPONSE_SCREENS: Record<string, ReactNode> = {
  '/alerts': <AlertsPage />,
  '/history': <HistorySearchPage />,
  '/incidents': <IncidentsPage />,
  '/incidents/:id': <IncidentDetailPage />,
}

// Screens restricted to responders.
const RESPONDER_SCREENS: Record<string, ReactNode> = {
  '/incidents/new': <NewIncidentPage />,
}

// Screens not built yet.
const placeholderPaths = AREAS.flatMap((area) =>
  area.pages.map((page) => page.path),
).filter((path) => path !== '/home' && !(path in SCREENS))

export const routes: RouteObject[] = [
  {
    path: '/',
    lazy: () =>
      import('@/pages/LandingPage').then((m) => ({
        Component: m.LandingPage,
      })),
  },
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },

  // Public status pages.
  {
    path: '/status',
    lazy: () =>
      import('@/pages/StatusPage').then((m) => ({
        Component: m.StatusPage,
      })),
  },
  {
    path: '/status/:slug',
    lazy: () =>
      import('@/pages/StatusPage').then((m) => ({
        Component: m.StatusPage,
      })),
  },

  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/home', element: <HomePage /> },

          ...Object.entries(SCREENS).map(([path, element]) => ({
            path,
            element,
          })),

          ...placeholderPaths.map((path) => ({
            path: `${path}/*`,
            element: <NotBuiltYetPage />,
          })),

          ...Object.entries(RESPONSE_SCREENS).map(([path, element]) => ({
            path,
            element,
          })),

          {
            element: <RequireAuth roles={['ADMIN', 'ENGINEER']} />,
            children: Object.entries(RESPONDER_SCREENS).map(
              ([path, element]) => ({
                path,
                element,
              }),
            ),
          },

          {
            element: <RequireAuth roles={['ADMIN']} />,
            children: ADMIN_PAGES.map((page) => ({
              path: page.path,
              element: ADMIN_SCREENS[page.path] ?? <NotBuiltYetPage />,
            })),
          },

          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(routes)
