import { Globe, LogOut, Search, Users, Webhook } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { findArea, findPage } from '@/app/navigation'
import { useAuth } from '@/auth/AuthProvider'
import { can, ROLE_LABEL } from '@/auth/roles'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ThemeToggle } from './ThemeToggle'
import { Wordmark } from './Wordmark'

const initials = (name: string) =>
  name
    .split(' ')
    .map((part) => part[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()

export function TopBar({ onSearch }: { onSearch: () => void }) {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const { user, logout } = useAuth()
  const area = findArea(pathname)
  const page = findPage(pathname)

  return (
    <header className="sticky top-0 z-30 flex h-[60px] items-center gap-5 border-b bg-background/85 px-4 backdrop-blur-md sm:px-8">
      <Link to="/home" aria-label="Settle home" className="text-foreground no-underline hover:no-underline">
        <Wordmark />
      </Link>

      <nav aria-label="Breadcrumb" className="hidden min-w-0 items-center gap-2 text-[13px] whitespace-nowrap text-muted-foreground sm:flex">
        <span className="text-input">/</span>
        <span>{area?.label ?? page?.label ?? ''}</span>
        {page && area && page.label !== area.label && (
          <>
            <span className="text-input">/</span>
            <span className="font-semibold text-foreground">{page.label}</span>
          </>
        )}
      </nav>

      <div className="flex-1" />

      <button
        type="button"
        onClick={onSearch}
        className="hidden h-[34px] items-center gap-2 rounded-full border bg-card px-3 text-[12.5px] text-muted-foreground transition-colors hover:bg-muted md:inline-flex"
      >
        <Search className="size-3.5" aria-hidden="true" />
        Go to…
        <kbd className="rounded-xs border px-1 font-mono text-[11px]">⌘K</kbd>
      </button>

      <ThemeToggle />

      {user && (
        <DropdownMenu>
          <DropdownMenuTrigger
            aria-label="Account"
            className="grid size-[34px] place-items-center rounded-full bg-primary text-[12px] font-bold text-primary-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {initials(user.displayName)}
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-60">
            <DropdownMenuLabel className="flex flex-col gap-0.5">
              <span className="text-[13.5px] font-semibold">{user.displayName}</span>
              <span className="text-xs font-normal text-muted-foreground">{ROLE_LABEL[user.role]}</span>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            {can(user.role, 'configure') && (
              <>
                <DropdownMenuItem onSelect={() => navigate('/users')}>
                  <Users aria-hidden="true" />
                  Users
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => navigate('/tools')}>
                  <Webhook aria-hidden="true" />
                  Tools
                </DropdownMenuItem>
              </>
            )}
            <DropdownMenuItem onSelect={() => navigate('/')}>
              <Globe aria-hidden="true" />
              Settle website
            </DropdownMenuItem>
            <DropdownMenuItem
              onSelect={async () => {
                await logout()
                navigate('/login')
              }}
            >
              <LogOut aria-hidden="true" />
              Log out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </header>
  )
}
