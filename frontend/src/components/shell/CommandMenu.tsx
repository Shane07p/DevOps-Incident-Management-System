import { ArrowUpRight, Keyboard, Moon, Plus, Sun } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { ACCOUNT_PAGES, ADMIN_PAGES, AREAS, type NavPage } from '@/app/navigation'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { useTheme } from '@/components/theme/ThemeProvider'
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandShortcut,
} from '@/components/ui/command'

/** ⌘K / Ctrl+K (opened by the shell's shortcuts): quick actions, and any page by name. */
export function CommandMenu({
  open,
  onOpenChange,
  onDeclare,
  onShortcuts,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onDeclare?: () => void
  onShortcuts: () => void
}) {
  const navigate = useNavigate()
  const { user } = useAuth()
  const { resolvedTheme, setTheme } = useTheme()
  const dark = resolvedTheme === 'dark'

  const run = (action: () => void) => {
    onOpenChange(false)
    action()
  }

  const pageItem = (page: NavPage, group: string) => (
    <CommandItem key={page.path} value={`${group} ${page.label}`} onSelect={() => run(() => navigate(page.path))}>
      <page.icon aria-hidden="true" />
      {page.label}
    </CommandItem>
  )

  return (
    <CommandDialog open={open} onOpenChange={onOpenChange} title="Go to" description="Search for a page or an action">
      <CommandInput placeholder="Search pages or actions…" />
      <CommandList>
        <CommandEmpty>Nothing with that name.</CommandEmpty>
        <CommandGroup heading="Quick actions">
          {onDeclare && (
            <CommandItem value="Declare an incident new" onSelect={() => run(onDeclare)}>
              <Plus aria-hidden="true" />
              Declare an incident
              <CommandShortcut>C</CommandShortcut>
            </CommandItem>
          )}
          <CommandItem value="Keyboard shortcuts" onSelect={() => run(onShortcuts)}>
            <Keyboard aria-hidden="true" />
            Keyboard shortcuts
            <CommandShortcut>?</CommandShortcut>
          </CommandItem>
          <CommandItem value="Theme light dark" onSelect={() => run(() => setTheme(dark ? 'light' : 'dark'))}>
            {dark ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />}
            {dark ? 'Switch to light theme' : 'Switch to dark theme'}
          </CommandItem>
          <CommandItem value="Settle website landing" onSelect={() => run(() => navigate('/'))}>
            <ArrowUpRight aria-hidden="true" />
            Settle website
          </CommandItem>
        </CommandGroup>
        {AREAS.map((area) => (
          <CommandGroup key={area.id} heading={area.label}>
            {area.pages.map((page) => pageItem(page, area.label))}
          </CommandGroup>
        ))}
        {can(user?.role, 'configure') && (
          <CommandGroup heading="Admin">{ADMIN_PAGES.map((page) => pageItem(page, 'Admin'))}</CommandGroup>
        )}
        <CommandGroup heading="Account">{ACCOUNT_PAGES.map((page) => pageItem(page, 'Account'))}</CommandGroup>
      </CommandList>
    </CommandDialog>
  )
}
