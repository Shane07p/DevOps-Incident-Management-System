import { useRef, type MouseEvent } from 'react'
import { Link } from 'react-router-dom'
import { useIstTime } from '@/components/app/IstClock'
import { Wordmark } from '@/components/shell/Wordmark'

const COLUMNS = [
  {
    title: 'Product',
    links: [
      { label: 'How it works', href: '#how' },
      { label: 'Features', href: '#features' },
      { label: 'Monitoring', href: '#monitoring' },
      { label: 'Status page', href: '#status' },
      { label: 'Incident replay', href: '#replay' },
    ],
  },
  {
    title: 'App',
    links: [
      { label: 'Log in', to: '/login' },
      { label: 'Your dashboard', to: '/home' },
      { label: 'Public status page', to: '/status' },
    ],
  },
]

/** Footer with a large outlined wordmark that lights up under the pointer. */
export function LandingFooter() {
  const { hhmmss } = useIstTime()
  const glow = useRef<SVGRadialGradientElement>(null)

  const onMove = (event: MouseEvent<HTMLDivElement>) => {
    const svg = event.currentTarget.querySelector('svg')
    if (!svg || !glow.current) return
    const box = svg.getBoundingClientRect()
    glow.current.setAttribute('cx', (((event.clientX - box.left) / box.width) * 1000).toFixed(0))
    glow.current.setAttribute('cy', (((event.clientY - box.top) / box.height) * 250).toFixed(0))
  }

  return (
    <footer className="border-t bg-background">
      <div className="mx-auto grid max-w-(--page-max) grid-cols-[minmax(min(100%,300px),2fr)_repeat(auto-fit,minmax(160px,1fr))] gap-10 px-4 pt-[72px] sm:px-8">
        <div className="flex max-w-[340px] flex-col gap-3.5">
          <Wordmark size={28} />
          <p className="text-pretty text-muted-foreground">
            Incident management for software teams. Register services, page on-call, track SLAs, and learn from every incident.
          </p>
          <Link to="/status" className="inline-flex h-[30px] items-center gap-2 self-start rounded-full border px-3 text-[13px] whitespace-nowrap text-foreground hover:no-underline">
            <span className="size-[7px] rounded-full bg-health-operational" aria-hidden="true" />
            All Settle systems operational
          </Link>
        </div>
        {COLUMNS.map((column) => (
          <div key={column.title} className="flex flex-col gap-3">
            <span className="text-sm font-bold">{column.title}</span>
            {column.links.map((link) =>
              'to' in link ? (
                <Link key={link.label} to={link.to} className="text-[14.5px] text-muted-foreground hover:text-foreground hover:no-underline">
                  {link.label}
                </Link>
              ) : (
                <a key={link.label} href={link.href} className="text-[14.5px] text-muted-foreground hover:text-foreground hover:no-underline">
                  {link.label}
                </a>
              ),
            )}
          </div>
        ))}
      </div>

      <div
        className="mx-auto mt-10 max-w-(--page-max) px-4 sm:px-8"
        onMouseMove={onMove}
        onMouseLeave={() => glow.current?.setAttribute('cx', '-400')}
      >
        <svg viewBox="0 0 1000 250" className="block h-auto w-full" aria-hidden="true">
          <defs>
            <radialGradient ref={glow} id="wordGlow" gradientUnits="userSpaceOnUse" cx="-400" cy="125" r="260">
              <stop offset="0%" stopColor="white" />
              <stop offset="100%" stopColor="black" />
            </radialGradient>
            <mask id="wordMask">
              <rect width="1000" height="250" fill="url(#wordGlow)" />
            </mask>
            <linearGradient id="wordFill" x1="0" x2="1">
              <stop offset="0%" stopColor="var(--ring)" />
              <stop offset="100%" stopColor="var(--glow)" />
            </linearGradient>
          </defs>
          {['none', 'url(#wordFill)'].map((fill) => (
            <text
              key={fill}
              x="500"
              y="225"
              textAnchor="middle"
              fontFamily="Host Grotesk Variable"
              fontWeight="800"
              style={{ fontStretch: '62%' }}
              fontSize="290"
              letterSpacing="-4"
              textLength="940"
              lengthAdjust="spacingAndGlyphs"
              fill={fill}
              stroke={fill === 'none' ? 'var(--border)' : undefined}
              strokeWidth={fill === 'none' ? 2 : undefined}
              mask={fill === 'none' ? undefined : 'url(#wordMask)'}
            >
              SETTLE
            </text>
          ))}
        </svg>
      </div>

      <div className="border-t">
        <div className="mx-auto flex max-w-(--page-max) flex-wrap justify-between gap-4 px-4 py-5 text-[13.5px] text-muted-foreground sm:px-8">
          <span className="whitespace-nowrap">IT314 course project, Group 2</span>
          <span className="whitespace-nowrap">Built with React, shadcn/ui, and Motion</span>
          <span className="font-mono text-[12.5px] whitespace-nowrap tnum">Times in IST · {hhmmss}</span>
        </div>
      </div>
    </footer>
  )
}
