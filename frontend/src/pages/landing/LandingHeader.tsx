import { ChevronDown, Menu, X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Wordmark } from '@/components/shell/Wordmark'
import { cn } from '@/lib/utils'
import { Container } from './parts'

const PRODUCT_MENU = [
  {
    title: 'Respond',
    links: [
      { label: 'Incident board', hint: 'Most urgent incident first', href: '#product' },
      { label: 'Acknowledge and resolve', hint: 'From page to postmortem', href: '#flow' },
    ],
  },
  {
    title: 'Monitor',
    links: [
      { label: 'Uptime checks', hint: 'From several locations at once', href: '#monitoring' },
      { label: 'Alert intake', hint: 'One webhook for every tool', href: '#tools' },
    ],
  },
]

const LINKS = [
  { label: 'Workflow', href: '#flow' },
  { label: 'Monitoring', href: '#monitoring' },
  { label: 'Integrations', href: '#tools' },
  { label: 'Questions', href: '#questions' },
]

const link = 'rounded-md px-3 py-2 text-[15px] font-medium text-foreground/85 hover:text-foreground hover:no-underline'

export function LandingHeader() {
  const [open, setOpen] = useState(false)

  return (
    <header className="fixed inset-x-0 top-0 z-50 border-b border-border bg-background/85 backdrop-blur-md">
      <a href="#top" className="sr-only focus:not-sr-only focus:absolute focus:top-3 focus:left-3 focus:rounded-md focus:bg-card focus:px-3 focus:py-2">
        Skip to content
      </a>
      <Container className="flex h-[72px] items-center gap-6">
        <a href="#top" aria-label="Settle home" className="text-foreground hover:no-underline">
          <Wordmark size={24} />
        </a>

        <nav aria-label="Main" className="hidden flex-1 items-center justify-center gap-1 lg:flex">
          <div className="group relative">
            <a href="#product" className={cn(link, 'inline-flex items-center gap-1')}>
              Product
              <ChevronDown aria-hidden="true" className="size-3.5 transition-transform group-focus-within:rotate-180 group-hover:rotate-180" />
            </a>
            {/* Opens on hover and on keyboard focus, so every link in it is reachable with Tab. */}
            <div className="invisible absolute top-full left-1/2 w-[520px] -translate-x-1/2 pt-3 opacity-0 transition-opacity group-focus-within:visible group-focus-within:opacity-100 group-hover:visible group-hover:opacity-100">
              <div className="grid grid-cols-2 gap-6 rounded-xl border border-border bg-popover p-5 shadow-lg">
                {PRODUCT_MENU.map((column) => (
                  <div key={column.title} className="flex flex-col gap-1">
                    <p className="px-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">{column.title}</p>
                    {column.links.map((item) => (
                      <a key={item.href} href={item.href} className="flex flex-col rounded-md px-2 py-2 hover:bg-secondary hover:no-underline">
                        <span className="text-sm font-medium text-foreground">{item.label}</span>
                        <span className="text-xs text-muted-foreground">{item.hint}</span>
                      </a>
                    ))}
                  </div>
                ))}
              </div>
            </div>
          </div>
          {LINKS.map((item) => (
            <a key={item.href} href={item.href} className={link}>
              {item.label}
            </a>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-2 lg:ml-0">
          <Link to="/login" className={cn(link, 'hidden sm:inline-flex')}>
            Sign in
          </Link>
          <Link
            to="/signup"
            className="inline-flex h-11 items-center rounded-md bg-primary px-5 text-[15px] font-medium text-primary-foreground hover:bg-primary/90 hover:no-underline"
          >
            Create a workspace
          </Link>
          <button
            type="button"
            aria-label={open ? 'Close menu' : 'Menu'}
            aria-expanded={open}
            aria-controls="landing-menu"
            onClick={() => setOpen((value) => !value)}
            className="grid size-11 place-items-center rounded-md text-foreground hover:bg-secondary lg:hidden"
          >
            {open ? <X className="size-5" aria-hidden="true" /> : <Menu className="size-5" aria-hidden="true" />}
          </button>
        </div>
      </Container>

      {open && (
        <nav id="landing-menu" aria-label="Main" className="border-t border-border lg:hidden">
          <Container className="flex flex-col py-3">
            {[{ label: 'Product', href: '#product' }, ...LINKS].map((item) => (
              <a key={item.href} href={item.href} onClick={() => setOpen(false)} className={cn(link, 'py-3')}>
                {item.label}
              </a>
            ))}
            <Link to="/login" className={cn(link, 'py-3 sm:hidden')}>
              Sign in
            </Link>
          </Container>
        </nav>
      )}
    </header>
  )
}
