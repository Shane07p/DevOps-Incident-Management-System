import { Fragment } from 'react'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { GO_TO, modKey } from './useGlobalShortcuts'

function Kbd({ children }: { children: string }) {
  return (
    <kbd className="inline-grid h-6 min-w-6 place-items-center rounded-[5px] border bg-muted px-1.5 font-mono text-[11px] text-foreground">
      {children}
    </kbd>
  )
}

/** The "?" list. Declaring is listed only for roles that may declare. */
export function ShortcutsDialog({
  open,
  onOpenChange,
  canDeclare,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  canDeclare: boolean
}) {
  const groups: { name: string; rows: { label: string; keys: string[]; then?: boolean }[] }[] = [
    {
      name: 'Anywhere',
      rows: [
        { label: 'Search pages and actions', keys: [modKey, 'K'] },
        { label: 'Search, one key', keys: ['/'] },
        ...(canDeclare ? [{ label: 'Declare an incident', keys: ['C'] }] : []),
        { label: 'Show this list', keys: ['?'] },
        { label: 'Close a dialog or menu', keys: ['Esc'] },
      ],
    },
    { name: 'Go to', rows: GO_TO.map((page) => ({ label: page.label, keys: ['G', page.key.toUpperCase()], then: true })) },
  ]

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[440px] rounded-[20px]">
        <DialogHeader>
          <DialogTitle className="text-[18px] font-medium tracking-[-0.01em]">Keyboard shortcuts</DialogTitle>
          <DialogDescription>Single keys work when you are not typing in a field.</DialogDescription>
        </DialogHeader>
        {groups.map((group) => (
          <section key={group.name} aria-label={group.name}>
            <h3 className="mb-1.5 text-[12px] font-medium text-muted-foreground">{group.name}</h3>
            <ul className="divide-y">
              {group.rows.map((row) => (
                <li key={row.label} className="flex items-center justify-between gap-4 py-2 text-[13px]">
                  <span>{row.label}</span>
                  <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                    {row.keys.map((key, index) => (
                      <Fragment key={key + index}>
                        {index > 0 && <span>{row.then ? 'then' : '+'}</span>}
                        <Kbd>{key}</Kbd>
                      </Fragment>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </DialogContent>
    </Dialog>
  )
}
