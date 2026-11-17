import { ArrowUpRight, ChevronRight, ChevronsUpDown, Key, LogOut, Search, Shield, Users, Webhook } from 'lucide-react'
import { useId, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ADMIN_PAGES, AREAS, type NavPage } from '@/app/navigation'
import { useAuth } from '@/auth/AuthProvider'
import { can, ROLE_LABEL } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { modKey } from './useGlobalShortcuts'
import { Wordmark } from './Wordmark'

const isOn = (pathname: string, path: string) => pathname === path || pathname.startsWith(`${path}/`)

const rowClass =
  'relative flex h-[34px] w-full items-center gap-2.5 rounded-[5px] px-[11px] text-[12px] text-sidebar-foreground transition-colors outline-none hover:bg-sidebar-accent hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring [&_svg]:size-[17px] [&_svg]:shrink-0'

function NavItem({ page, nested, onNavigate }: { page: NavPage; nested?: boolean; onNavigate?: () => void }) {
  const { pathname } = useLocation()
  const current = isOn(pathname, page.path)
  return (
    <Link
      to={page.path}
      onClick={onNavigate}
      aria-current={current ? 'page' : undefined}
      className={cn(
        rowClass,
        'my-0.5 no-underline hover:no-underline',
        nested && 'h-8 px-2',
        current && 'bg-primary/8 text-primary hover:bg-primary/12 hover:text-primary',
      )}
    >
      {current && <span aria-hidden="true" className="absolute top-2 -left-px h-[18px] w-0.5 rounded-full bg-primary" />}
      <page.icon aria-hidden="true" />
      <span className="truncate">{page.label}</span>
    </Link>
  )
}

/** A group that opens and closes. It starts open when it holds the current page; after that the person decides. */
function NavGroup({
  label,
  icon: Icon,
  pages,
  onNavigate,
}: {
  label: string
  icon: NavPage['icon']
  pages: NavPage[]
  onNavigate?: () => void
}) {
  const { pathname } = useLocation()
  const holdsCurrent = pages.some((page) => isOn(pathname, page.path))
  const [chosen, setChosen] = useState<boolean | null>(null)
  const open = chosen ?? holdsCurrent
  const listId = useId()
  return (
    <div className="mt-[7px]">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setChosen(!open)}
        className={rowClass}
      >
        <Icon aria-hidden="true" />
        <span className="flex-1 text-left">{label}</span>
        <ChevronRight
          aria-hidden="true"
          className={cn('size-3.5! transition-transform duration-[var(--dur-fast)]', open && 'rotate-90')}
        />
      </button>
      {open && (
        <div id={listId} className="ml-[21px] border-l border-sidebar-border pl-[9px]">
          {pages.map((page) => (
            <NavItem key={page.path} page={page} nested onNavigate={onNavigate} />
          ))}
        </div>
      )}
    </div>
  )
}

function AccountMenu({ onNavigate }: { onNavigate?: () => void }) {
  const navigate = useNavigate()
  const { user, org, logout } = useAuth()
  if (!user) return null
  const go = (path: string) => {
    onNavigate?.()
    navigate(path)
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label="Account"
        className="flex w-full items-center gap-[9px] rounded-md px-[7px] py-[15px] text-left outline-none hover:bg-sidebar-accent focus-visible:ring-2 focus-visible:ring-ring"
      >
        <Avatar name={user.displayName} />
        <span className="flex min-w-0 flex-1 flex-col text-[12px] leading-[18px]">
          <span className="truncate text-foreground">{user.displayName}</span>
          <span className="text-muted-foreground">{ROLE_LABEL[user.role]}</span>
        </span>
        <ChevronsUpDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
      </DropdownMenuTrigger>
      <DropdownMenuContent side="top" align="start" sideOffset={10} className="w-60">
        <DropdownMenuLabel className="flex flex-col gap-0.5">
          <span className="text-[13.5px] font-semibold">{user.displayName}</span>
          <span className="text-xs font-normal text-muted-foreground">{ROLE_LABEL[user.role]}</span>
          {org && <span className="text-xs font-normal text-muted-foreground">{org.name}</span>}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => go('/tokens')}>
          <Key aria-hidden="true" />
          Access tokens
        </DropdownMenuItem>
        {can(user.role, 'configure') && (
          <>
            <DropdownMenuItem onSelect={() => go('/users')}>
              <Users aria-hidden="true" />
              Members
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => go('/tools')}>
              <Webhook aria-hidden="true" />
              Tools
            </DropdownMenuItem>
          </>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onSelect={async () => {
            onNavigate?.()
            await logout()
            navigate('/login')
          }}
        >
          <LogOut aria-hidden="true" />
          Log out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * Everything in the sidebar: the wordmark, the org, search, the areas from `navigation.ts`, and the account.
 * The shell shows it as a column from 760 px and inside the drawer below that; `onNavigate` closes the drawer.
 */
export function SidebarContent({ onSearch, onNavigate }: { onSearch: () => void; onNavigate?: () => void }) {
  const { user, org } = useAuth()

  return (
    <div className="flex h-full min-h-0 flex-col px-[13px] pt-[25px]">
      <Link
        to="/home"
        onClick={onNavigate}
        aria-label="Settle home"
        className="mb-6 self-start px-[11px] text-foreground no-underline hover:no-underline"
      >
        <Wordmark size={30} />
      </Link>

      {org && (
        <div className="flex items-center gap-[9px] rounded-md border bg-card/40 px-[9px] py-2.5">
          <span
            aria-hidden="true"
            className="grid size-[26px] shrink-0 place-items-center rounded-[5px] bg-foreground text-[13px] font-bold text-background"
          >
            {org.name.charAt(0).toUpperCase()}
          </span>
          <span className="flex min-w-0 flex-col text-[12px] leading-[18px] font-medium">
            <span className="truncate">{org.name}</span>
            <span className="truncate font-mono text-[11px] font-normal text-muted-foreground">{org.slug}</span>
          </span>
        </div>
      )}

      <button
        type="button"
        onClick={onSearch}
        className="mt-2 flex items-center gap-2 rounded-md px-2 py-2.5 text-[12px] text-muted-foreground transition-colors outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
      >
        <Search className="size-[15px]" aria-hidden="true" />
        <span className="flex-1 text-left">Search anything</span>
        <kbd className="rounded-[3px] border px-1 font-mono text-[11px]">{modKey} K</kbd>
      </button>

      <nav aria-label="Main" className="-mx-1 mt-1 min-h-0 flex-1 overflow-y-auto px-1 pb-4">
        {AREAS.map((area) => {
          if (area.collapsible) {
            return <NavGroup key={area.id} label={area.label} icon={area.icon} pages={area.pages} onNavigate={onNavigate} />
          }
          if (area.pages.length === 1) return <NavItem key={area.id} page={area.pages[0]} onNavigate={onNavigate} />
          return (
            <div key={area.id} role="group" aria-labelledby={`nav-${area.id}`}>
              <div id={`nav-${area.id}`} className="mx-[11px] mt-2 mb-1 text-[12px] font-medium text-muted-foreground">
                {area.label}
              </div>
              {area.pages.map((page) => (
                <NavItem key={page.path} page={page} onNavigate={onNavigate} />
              ))}
            </div>
          )
        })}
        {can(user?.role, 'configure') && <NavGroup label="Admin" icon={Shield} pages={ADMIN_PAGES} onNavigate={onNavigate} />}
      </nav>

      <div className="border-t border-sidebar-border pt-2">
        <Link to="/" onClick={onNavigate} className={cn(rowClass, 'no-underline hover:no-underline')}>
          <ArrowUpRight aria-hidden="true" />
          Settle website
        </Link>
        <AccountMenu onNavigate={onNavigate} />
      </div>
    </div>
  )
}
