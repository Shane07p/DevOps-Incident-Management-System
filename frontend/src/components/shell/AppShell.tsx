import { useCallback, useState } from 'react'
import { Outlet } from 'react-router-dom'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet'
import { DeclareIncidentDialog } from '@/pages/incident/DeclareIncidentDialog'
import { CommandMenu } from './CommandMenu'
import { DeclareContext } from './declare'
import { ShortcutsDialog } from './ShortcutsDialog'
import { useGlobalShortcuts } from './useGlobalShortcuts'
import { SidebarContent } from './Sidebar'
import { TopBar } from './TopBar'

/** Every logged-in page: the sidebar (a drawer below 760 px), the top bar, and the page. */
export function AppShell() {
  const { user } = useAuth()
  const [searchOpen, setSearchOpen] = useState(false)
  const [shortcutsOpen, setShortcutsOpen] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [declareOpen, setDeclareOpen] = useState(false)

  const openSearch = useCallback(() => setSearchOpen(true), [])
  const toggleSearch = useCallback(() => setSearchOpen((open) => !open), [])
  const openShortcuts = useCallback(() => setShortcutsOpen(true), [])
  const closeDrawer = useCallback(() => setDrawerOpen(false), [])
  const canDeclare = can(user?.role, 'respond')
  const declare = useCallback(() => setDeclareOpen(true), [])
  const onDeclare = canDeclare ? declare : undefined

  useGlobalShortcuts({ onToggleSearch: toggleSearch, onShortcuts: openShortcuts, onDeclare })

  return (
    <div className="min-h-screen bg-background text-foreground">
      <a
        href="#main"
        className="sr-only z-50 rounded-md bg-primary px-3 py-2 text-primary-foreground focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        Skip to content
      </a>

      <aside className="fixed inset-y-0 left-0 z-40 hidden w-[218px] flex-col border-r border-sidebar-border bg-sidebar nav:flex">
        <SidebarContent onSearch={openSearch} />
      </aside>

      <div className="min-w-0 nav:pl-[218px]">
        <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
          <TopBar onSearch={openSearch} onShortcuts={openShortcuts} onDeclare={onDeclare} />
          <SheetContent side="left" className="w-[260px] max-w-[85vw] gap-0 border-sidebar-border bg-sidebar p-0 sm:max-w-[260px]">
            <SheetTitle className="sr-only">Navigation</SheetTitle>
            <SheetDescription className="sr-only">Every page in Settle</SheetDescription>
            <SidebarContent
              onSearch={() => {
                closeDrawer()
                openSearch()
              }}
              onNavigate={closeDrawer}
            />
          </SheetContent>
        </Sheet>

        <main id="main" tabIndex={-1} className="min-w-0 px-4 pt-6 pb-11 outline-none nav:px-[34px] nav:pt-[34px]">
          <DeclareContext value={onDeclare}>
            <Outlet />
          </DeclareContext>
        </main>
      </div>

      <CommandMenu open={searchOpen} onOpenChange={setSearchOpen} onDeclare={onDeclare} onShortcuts={openShortcuts} />
      <ShortcutsDialog open={shortcutsOpen} onOpenChange={setShortcutsOpen} canDeclare={canDeclare} />
      {canDeclare && <DeclareIncidentDialog open={declareOpen} onOpenChange={setDeclareOpen} />}
    </div>
  )
}
