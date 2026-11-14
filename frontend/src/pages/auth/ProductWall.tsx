import { useEffect, useRef, type CSSProperties } from 'react'

/* From the redesign preview (#267). Sign-in and sign-up: a tilted wall of Settle's own screens, drifting slowly in three columns.
   The org name the visitor types appears in every screen's top bar and their name on the screens
   that name a person. The pointer tilts the wall; a hovered screen lifts and plays its small
   animation. Signing in turns the wall to face the visitor before the app opens. Reduced motion
   keeps the wall still. The screens are drawn in HTML, so they follow the theme. */

type TileProps = { org: string; person: string }

const firstName = (person: string) => person.split(' ')[0]

function Bar({ org, page }: { org: string; page: string }) {
  return (
    <div className="pw-bar">
      <svg className="pw-mark" viewBox="0 0 16 8">
        <path d="M1 5c2-3.4 4-3.4 6 0s4 3.4 6 0" />
      </svg>
      <b>{org}</b>
      <span>/</span>
      <span>{page}</span>
    </div>
  )
}

function OverviewTile({ org, person }: TileProps) {
  return (
    <div className="pw-tile pw-overview">
      <Bar org={org} page="Overview" />
      <div className="pw-body">
        <p className="pw-k">Waiting for acknowledgement</p>
        <div className="pw-card">
          <div className="pw-row">
            <span className="pw-sev pw-sev1">SEV1</span>
            <span className="pw-title">UPI payment authorisations failing</span>
          </div>
          <div className="pw-row pw-between">
            <span className="pw-clock">
              <span className="pw-dot" />
              Response 12m of 15m
            </span>
            <span className="pw-btn">
              <span>Acknowledge and take Lead</span>
              <span>{person ? `${firstName(person)} is Lead` : 'You are Lead'}</span>
            </span>
          </div>
        </div>
        <div className="pw-stats">
          <span>
            <b>4</b>Open
          </span>
          <span>
            <b className="pw-amber">1</b>At risk
          </span>
          <span>
            <b className="pw-red">1</b>Breached
          </span>
        </div>
      </div>
    </div>
  )
}

function IncidentTile({ org, person }: TileProps) {
  const lead = person || 'Asha Patel'
  return (
    <div className="pw-tile pw-incident">
      <Bar org={org} page="Incidents / #1042" />
      <div className="pw-body">
        <p className="pw-title pw-lg">Checkout failures after the latest deployment</p>
        <div className="pw-row">
          <span className="pw-sev pw-sev2">SEV2</span>
          <span className="pw-chip">Investigating</span>
          <span className="pw-muted">Lead {lead}</span>
        </div>
        <div className="pw-clocks">
          <div>
            <span className="pw-k">Response</span>
            <b className="pw-green">Met in 4m</b>
          </div>
          <div>
            <span className="pw-k">Resolution</span>
            <b>
              1h 12m <small>of 4h</small>
            </b>
            <span className="pw-track">
              <i />
            </span>
          </div>
        </div>
        <ol className="pw-timeline">
          <li>
            <time>14:02</time>Grafana alert opened the incident
          </li>
          <li>
            <time>14:06</time>
            {firstName(lead)} acknowledged and became Lead
          </li>
          <li>
            <time>14:15</time>Deploy 4f2c rolled back
          </li>
        </ol>
      </div>
    </div>
  )
}

const MAP_NODES: [string, number, number, string?][] = [
  ['Web', 34, 86],
  ['Checkout API', 140, 44],
  ['Search', 140, 132],
  ['Payments', 262, 44, 'red'],
  ['Orders', 262, 132, 'amber'],
  ['Ledger', 366, 44],
  ['Inventory', 366, 132],
]
const MAP_EDGES: [number, number, string?][] = [
  [0, 1],
  [0, 2],
  [1, 3, 'red'],
  [1, 4],
  [3, 5, 'red'],
  [4, 6],
]

function MapTile({ org }: TileProps) {
  return (
    <div className="pw-tile pw-map">
      <Bar org={org} page="Service map" />
      <div className="pw-body">
        <div className="pw-row pw-between">
          <span className="pw-k">Blast radius of Payments</span>
          <span className="pw-red pw-small">2 services affected</span>
        </div>
        <svg className="pw-graph" viewBox="0 0 400 170">
          {MAP_EDGES.map(([a, b, tone], i) => (
            <path
              key={i}
              className={`pw-edge ${tone ? 'is-' + tone : ''}`}
              d={`M${MAP_NODES[a][1]} ${MAP_NODES[a][2]} L${MAP_NODES[b][1]} ${MAP_NODES[b][2]}`}
            />
          ))}
          {MAP_NODES.map(([label, x, y, tone]) => (
            <g key={label} className={`pw-node ${tone ? 'is-' + tone : ''}`}>
              <circle cx={x} cy={y} r="9" />
              <text x={x} y={y + 26} textAnchor="middle">
                {label}
              </text>
            </g>
          ))}
        </svg>
      </div>
    </div>
  )
}

const UPTIME: [string, string, Record<number, string>][] = [
  ['Checkout API', '99.92%', { 21: 'amber' }],
  ['Payments', '99.71%', { 9: 'amber', 27: 'red', 28: 'amber' }],
  ['Search', '100%', {}],
]

function StatusTile({ org }: TileProps) {
  return (
    <div className="pw-tile pw-status">
      <Bar org={org} page="Status page" />
      <div className="pw-body">
        <p className="pw-banner">Payments: degraded performance</p>
        {UPTIME.map(([name, pct, marks]) => (
          <div className="pw-uptime" key={name}>
            <div className="pw-row pw-between">
              <span>{name}</span>
              <span className="pw-muted">{pct}</span>
            </div>
            <div className="pw-bars">
              {Array.from({ length: 30 }, (_, i) => (
                <i key={i} className={marks[i] ? 'is-' + marks[i] : ''} style={{ '--i': i } as CSSProperties} />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function AlertsTile({ org }: TileProps) {
  const rows = [
    ['Grafana', 'payment_auth_errors above 5%', 'now'],
    ['Prometheus', 'upi_latency_p95 above 1.5 s', '1m'],
    ['Uptime', '/pay timed out from location C', '2m'],
    ['Heartbeat', 'settlement-job missed a beat', '4m'],
  ]
  return (
    <div className="pw-tile pw-alerts">
      <Bar org={org} page="Alerts" />
      <div className="pw-body">
        <p className="pw-k">18 alerts grouped into INC-1043</p>
        <ul className="pw-list">
          {rows.map(([source, text, time]) => (
            <li key={text}>
              <span className="pw-chip">{source}</span>
              <span className="pw-grow">{text}</span>
              <time className="pw-muted">{time}</time>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

function ScheduleTile({ org, person }: TileProps) {
  const now = person || 'Rohan Shah'
  return (
    <div className="pw-tile pw-schedule">
      <Bar org={org} page="Schedules" />
      <div className="pw-body">
        <p className="pw-k">On call now · Payments</p>
        <p className="pw-title pw-lg">
          {now} <span className="pw-muted">until 17:00</span>
        </p>
        <div className="pw-coverage">
          <span style={{ flex: 8 }}>{firstName(now)}</span>
          <span style={{ flex: 6 }}>Kavya</span>
          <span style={{ flex: 10 }}>Dev</span>
          <i className="pw-now" />
        </div>
        <p className="pw-muted pw-small">Next: Kavya Iyer at 17:00 · Fallback Asha Patel</p>
      </div>
    </div>
  )
}

function MetricTile({ org }: TileProps) {
  return (
    <div className="pw-tile pw-metric">
      <Bar org={org} page="Metrics · Payments" />
      <div className="pw-body">
        <div className="pw-row pw-between">
          <span className="pw-k">Error rate</span>
          <b className="pw-red">4.8%</b>
        </div>
        <svg className="pw-chart" viewBox="0 0 320 96">
          <path className="pw-limit" d="M0 62 H320" />
          <path
            className="pw-line"
            pathLength={1}
            d="M0 80 L30 78 L60 81 L90 77 L120 79 L150 76 L170 70 L185 30 L200 18 L220 24 L240 22 L260 40 L280 66 L300 74 L320 76"
          />
        </svg>
        <p className="pw-muted pw-small">Deploy 4f2c at 14:00 · alert at 14:02 · rolled back 14:15</p>
      </div>
    </div>
  )
}

const COLUMNS = [
  { tiles: [OverviewTile, StatusTile, MetricTile], seconds: 64, reverse: false },
  { tiles: [IncidentTile, MapTile, AlertsTile], seconds: 76, reverse: true },
  { tiles: [ScheduleTile, AlertsTile, OverviewTile], seconds: 70, reverse: false },
]

export function ProductWall({ org, person, entering }: { org: string; person: string; entering: boolean }) {
  const wall = useRef<HTMLDivElement>(null)

  // The pointer tilts the wall a few degrees, eased so it never snaps.
  useEffect(() => {
    const el = wall.current
    if (!el || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const side = el.closest('.login-brand-side') as HTMLElement | null
    const want = { x: 0, y: 0 }
    const have = { x: 0, y: 0 }
    let raf = 0
    const step = () => {
      have.x += (want.x - have.x) * 0.08
      have.y += (want.y - have.y) * 0.08
      el.style.setProperty('--tilt-x', `${(have.y * -3).toFixed(2)}deg`)
      el.style.setProperty('--tilt-y', `${(have.x * 4).toFixed(2)}deg`)
      raf = Math.abs(want.x - have.x) + Math.abs(want.y - have.y) > 0.002 ? requestAnimationFrame(step) : 0
    }
    const onMove = (e: PointerEvent) => {
      const r = (side || el).getBoundingClientRect()
      const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom
      want.x = inside ? ((e.clientX - r.left) / r.width) * 2 - 1 : 0
      want.y = inside ? ((e.clientY - r.top) / r.height) * 2 - 1 : 0
      if (!raf) raf = requestAnimationFrame(step)
    }
    window.addEventListener('pointermove', onMove)
    return () => {
      window.removeEventListener('pointermove', onMove)
      cancelAnimationFrame(raf)
    }
  }, [])

  const props = { org: org || 'Your workspace', person }
  return (
    <div className="product-wall" ref={wall} data-entering={entering || undefined} aria-hidden="true">
      <div className="pw-plane">
        {COLUMNS.map((col, c) => (
          <div
            className={'pw-col' + (col.reverse ? ' is-reverse' : '')}
            key={c}
            style={{ '--seconds': `${col.seconds}s` } as CSSProperties}
          >
            {[...col.tiles, ...col.tiles].map((Tile, i) => (
              <Tile key={i} {...props} />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}
