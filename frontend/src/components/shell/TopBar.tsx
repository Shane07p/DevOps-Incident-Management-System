import { ChevronRight, Keyboard, Menu, Plus, Search } from 'lucide-react'
import { Fragment, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { findArea, findPage } from '@/app/navigation'
import { useAuth } from '@/auth/AuthProvider'
import { Button } from '@/components/ui/button'
import { SheetTrigger } from '@/components/ui/sheet'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { NotificationBell } from './NotificationBell'
import { modKey } from './useGlobalShortcuts'
import { ThemeToggle, iconButtonClass } from './ThemeToggle'

function IconButton({ label, tip, onClick, className, children }: { label: string; tip: string; onClick: () => void; className?: string; children: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button type="button" aria-label={label} onClick={onClick} className={className ?? iconButtonClass}>
          {children}
        </button>
      </TooltipTrigger>
      <TooltipContent>{tip}</TooltipContent>
    </Tooltip>
  )
}

/**
 * The bar above every app page: the drawer button below 760 px (it must sit inside the shell's Sheet), where you
 * are, and the tools that work on any page.
 */
export function TopBar({
  onSearch,
  onShortcuts,
  onDeclare,
}: {
  onSearch: () => void
  onShortcuts: () => void
  onDeclare?: () => void
}) {
  const { pathname } = useLocation()
  const { user, org } = useAuth()
  const area = findArea(pathname)
  const page = findPage(pathname)
  const crumbs = [org?.name, area?.label ?? page?.label, page && area && page.label !== area.label ? page.label : undefined].filter(
    (crumb): crumb is string => Boolean(crumb),
  )

  return (
    <header className="sticky top-0 z-30 flex h-[62px] items-center gap-3 border-b bg-background px-4 nav:px-8">
      <SheetTrigger asChild>
        <button type="button" aria-label="Open navigation" className={`${iconButtonClass} -ml-1.5 nav:hidden`}>
          <Menu className="size-5" aria-hidden="true" />
        </button>
      </SheetTrigger>

      <nav aria-label="Breadcrumb" className="flex min-w-0 flex-1 items-center gap-2 text-[12px] whitespace-nowrap">
        {crumbs.map((crumb, index) => (
          <Fragment key={`${index}-${crumb}`}>
            {index > 0 && (
              <ChevronRight className="hidden size-[13px] shrink-0 text-muted-foreground nav:block" aria-hidden="true" />
            )}
            <span
              className={
                index === crumbs.length - 1
                  ? 'truncate text-foreground'
                  : // On a phone only the page shows; the org and area come back from 760 px.
                    'hidden truncate text-muted-foreground nav:inline'
              }
            >
              {crumb}
            </span>
          </Fragment>
        ))}
      </nav>

      <div className="flex shrink-0 items-center gap-1">
        <IconButton label="Search" tip={`Jump to a page · ${modKey} K`} onClick={onSearch}>
          <Search className="size-[17px]" aria-hidden="true" />
        </IconButton>
        <ThemeToggle />
        <IconButton
          label="Keyboard shortcuts"
          tip="Keyboard shortcuts · ?"
          onClick={onShortcuts}
          className={`${iconButtonClass} max-sm:hidden`}
        >
          <Keyboard className="size-[17px]" aria-hidden="true" />
        </IconButton>
        {user && <NotificationBell />}
        {/* Not on the declare form itself, whose submit button has the same name. */}
        {onDeclare && pathname !== '/incidents/new' && (
          <Button size="sm" onClick={onDeclare} className="ml-1.5 h-[30px] px-3 text-[13px]">
            <Plus aria-hidden="true" />
            <span className="max-sm:sr-only">Declare incident</span>
          </Button>
        )}
      </div>
    </header>
  )
}
