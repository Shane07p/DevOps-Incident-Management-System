import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Wordmark } from '@/components/shell/Wordmark'
import { Container } from './parts'

const COLUMNS = [
  { title: 'Product', links: [{ label: 'Incident board', href: '#product' }, { label: 'Workflow', href: '#flow' }] },
  { title: 'Monitoring', links: [{ label: 'Uptime checks', href: '#monitoring' }, { label: 'Integrations', href: '#tools' }] },
  { title: 'Help', links: [{ label: 'Questions', href: '#questions' }, { label: 'The record', href: '#settled' }] },
  { title: 'Account', links: [{ label: 'Sign in', to: '/login' }, { label: 'Create a workspace', to: '/signup' }] },
]

const link = 'text-sm text-foreground/90 hover:text-foreground hover:underline'

/** The closing call to action and the site links. */
export function LandingFooter() {
  return (
    <footer id="contact" aria-labelledby="close-heading" className="border-t border-border bg-background pt-24 pb-10">
      <Container>
        <h2 id="close-heading" className="max-w-[34rem] text-[clamp(2.25rem,4vw+0.5rem,3.25rem)] leading-[1.05] font-medium tracking-[-0.035em]">
          Create a workspace for your team
        </h2>
        <p className="mt-5 max-w-[42rem] text-lg leading-relaxed text-foreground/90">
          Sign-up creates your organisation. Add services, on-call schedules and your monitoring tools next.
        </p>
        <Link
          to="/signup"
          className="mt-10 flex h-20 items-center justify-between rounded-lg bg-primary px-8 text-[clamp(1.5rem,2vw+0.75rem,2rem)] font-medium tracking-[-0.02em] text-primary-foreground hover:bg-primary/90 hover:no-underline sm:px-12"
        >
          Create a workspace
          <ArrowRight aria-hidden="true" className="size-6" />
        </Link>

        <div className="mt-24 grid gap-10 sm:grid-cols-2 lg:grid-cols-12">
          <div className="flex flex-col gap-5 lg:col-span-4">
            <a href="#top" aria-label="Settle home" className="text-foreground hover:no-underline">
              <Wordmark size={24} />
            </a>
            <p className="max-w-[20rem] text-lg leading-snug font-medium tracking-[-0.01em]">Incident response and monitoring for software teams.</p>
          </div>
          {COLUMNS.map((column) => (
            <nav key={column.title} aria-label={column.title} className="flex flex-col gap-4 lg:col-span-2">
              <h3 className="text-sm text-muted-foreground">{column.title}</h3>
              <ul className="flex flex-col gap-3">
                {column.links.map((item) => (
                  <li key={item.label}>
                    {'to' in item && item.to ? (
                      <Link to={item.to} className={link}>
                        {item.label}
                      </Link>
                    ) : (
                      <a href={'href' in item ? item.href : undefined} className={link}>
                        {item.label}
                      </a>
                    )}
                  </li>
                ))}
              </ul>
            </nav>
          ))}
        </div>

        <div className="mt-16 flex flex-wrap justify-between gap-4 border-t border-border pt-6 text-xs text-muted-foreground">
          <span>© 2026 Settle</span>
          <span>Product names and logos are trademarks of their owners</span>
        </div>
      </Container>
    </footer>
  )
}
