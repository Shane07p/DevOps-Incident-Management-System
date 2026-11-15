import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Box, X } from 'lucide-react'
import { useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router-dom'
import { listIncidents, type IncidentSummary } from '@/api/incidents'
import { getBlastRadius, getServiceMap, type Health, type ServiceMap } from '@/api/serviceMap'
import { listServices } from '@/api/services'
import { SeverityBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { openIncidentsOf } from './services/serviceHealth'

const HEALTH: Record<Health, { label: string; dot: string; ring: string }> = {
  OPERATIONAL: { label: 'Operational', dot: 'bg-health-operational', ring: 'border-health-operational' },
  DEGRADED: { label: 'Degraded', dot: 'bg-health-degraded', ring: 'border-health-degraded' },
  DOWN: { label: 'Down', dot: 'bg-health-down', ring: 'border-health-down' },
  UNKNOWN: { label: 'Unknown', dot: 'bg-muted-foreground/50', ring: 'border-muted-foreground/40' },
}

const VIEWS = [
  { id: 'deps', label: 'Dependencies' },
  { id: 'blast', label: 'Blast radius' },
] as const
type View = (typeof VIEWS)[number]['id']

const NODE_WIDTH = 212
const NODE_HEIGHT = 64
const COLUMN_GAP = 104
const ROW_GAP = 26
const PAD = 24

type Node = ServiceMap['nodes'][number]
type Placed = Node & { x: number; y: number }

/**
 * Columns by how deep a service sits in the call chain: services nothing depends on sit on the left, and each
 * service sits one column right of the deepest service that depends on it, so every link runs left to right,
 * from a service to what it calls. Within a column, services sit near the ones that call them.
 */
function layout(map: ServiceMap): { nodes: Placed[]; width: number; height: number } {
  const callers = new Map<number, number[]>()
  for (const edge of map.edges) callers.set(edge.to, [...(callers.get(edge.to) ?? []), edge.from])
  const column = new Map<number, number>()
  const columnOf = (id: number, path: Set<number>): number => {
    const known = column.get(id)
    if (known !== undefined) return known
    // A cycle would never end; its services share a column instead.
    if (path.has(id)) return 0
    path.add(id)
    const above = callers.get(id) ?? []
    const value = above.length === 0 ? 0 : 1 + Math.max(...above.map((caller) => columnOf(caller, path)))
    path.delete(id)
    column.set(id, value)
    return value
  }
  const columns: Node[][] = []
  for (const node of map.nodes) (columns[columnOf(node.id, new Set())] ??= []).push(node)

  const row = new Map<number, number>()
  const placed: Placed[] = []
  let rows = 0
  columns.forEach((nodes = [], index) => {
    const mean = (node: Node) => {
      const rowsAbove = (callers.get(node.id) ?? []).map((caller) => row.get(caller)).filter((value) => value !== undefined)
      return rowsAbove.length === 0 ? Infinity : rowsAbove.reduce((sum, value) => sum + value, 0) / rowsAbove.length
    }
    nodes
      .toSorted((a, b) => mean(a) - mean(b) || a.name.localeCompare(b.name))
      .forEach((node, position) => {
        row.set(node.id, position)
        placed.push({ ...node, x: PAD + index * (NODE_WIDTH + COLUMN_GAP), y: PAD + position * (NODE_HEIGHT + ROW_GAP) })
        rows = Math.max(rows, position + 1)
      })
  })
  const columnCount = Math.max(1, columns.length)
  return {
    nodes: placed,
    width: 2 * PAD + columnCount * NODE_WIDTH + (columnCount - 1) * COLUMN_GAP,
    height: 2 * PAD + Math.max(1, rows) * NODE_HEIGHT + Math.max(0, rows - 1) * ROW_GAP,
  }
}

/**
 * The path of a link, from the right edge of the caller to the left edge of what it calls. A link to the next
 * column is one curve; a longer one runs along the gap between rows, so it never crosses a service in between.
 */
function linkPath(from: Placed, to: Placed) {
  const [x1, y1, x2, y2] = [from.x + NODE_WIDTH, from.y + NODE_HEIGHT / 2, to.x, to.y + NODE_HEIGHT / 2]
  const bend = COLUMN_GAP / 4
  if (x2 - x1 <= COLUMN_GAP) return `M ${x1} ${y1} C ${x1 + 2 * bend} ${y1}, ${x2 - 2 * bend} ${y2}, ${x2} ${y2}`
  const lane = y2 > y1 ? from.y + NODE_HEIGHT + ROW_GAP / 2 : from.y - ROW_GAP / 2
  const [a, b] = [x1 + 2 * bend, x2 - 2 * bend]
  return `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${a - bend} ${lane}, ${a} ${lane} L ${b} ${lane} C ${b + bend} ${lane}, ${x2 - bend} ${y2}, ${x2} ${y2}`
}

/** Dependencies or Blast radius, as radios: arrow keys move between them, and Tab lands on the chosen one. */
function ViewSwitch({ view, onChange }: { view: View; onChange: (view: View) => void }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  const onKeyDown = (event: KeyboardEvent, index: number) => {
    const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0
    if (step === 0) return
    event.preventDefault()
    const next = (index + step + VIEWS.length) % VIEWS.length
    onChange(VIEWS[next].id)
    refs.current[next]?.focus()
  }
  return (
    <div role="radiogroup" aria-label="What to highlight" className="flex w-fit gap-1 rounded-lg border bg-card p-1">
      {VIEWS.map((option, index) => (
        <button
          key={option.id}
          ref={(element) => {
            refs.current[index] = element
          }}
          type="button"
          role="radio"
          aria-checked={view === option.id}
          tabIndex={view === option.id ? 0 : -1}
          onClick={() => onChange(option.id)}
          onKeyDown={(event) => onKeyDown(event, index)}
          className={cn(
            'rounded-md px-3 py-1.5 text-[13px] text-muted-foreground transition-colors outline-none hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50',
            view === option.id && 'bg-secondary font-medium text-foreground',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

function ServiceList({ title, items, empty }: { title: string; items: { id: number; name: string; health?: Health }[]; empty: string }) {
  return (
    <section className="flex flex-col gap-2.5">
      <h3 className="text-[13px] font-medium">{title}</h3>
      {items.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">{empty}</p>
      ) : (
        <ul className="flex flex-col gap-2 text-[13px]">
          {items.map((item) => (
            <li key={item.id} className="flex items-center gap-2">
              <span className={cn('size-2 shrink-0 rounded-full', HEALTH[item.health ?? 'UNKNOWN'].dot)} aria-hidden="true" />
              {item.name}
              {item.health && <span className="sr-only">, {HEALTH[item.health].label}</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function OpenIncidents({ incidents }: { incidents: IncidentSummary[] }) {
  return (
    <section className="flex flex-col gap-2.5">
      <h3 className="text-[13px] font-medium">Open incidents</h3>
      {incidents.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">None.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {incidents.map((incident) => (
            <li key={incident.id} className="flex items-center gap-2.5 text-[13px]">
              <SeverityBadge severity={incident.severity} />
              <Link to={`/incidents/${incident.id}`} className="min-w-0 truncate hover:underline">
                {incident.title}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * Catalog → Service map (US-26): services with their health and open incidents, and what calls what. Dependencies
 * lights up what a chosen service calls and what calls it; Blast radius lights up everything that may break if it
 * fails, as the server works it out.
 */
export function ServiceMapPage() {
  const map = useQuery({ queryKey: ['service-map'], queryFn: getServiceMap })
  // Open incidents and owners add to the map; it still draws if either fails.
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const [view, setView] = useState<View>('deps')
  const [selected, setSelected] = useState<number | null>(null)
  const blast = useQuery({
    queryKey: ['blast-radius', selected],
    queryFn: () => getBlastRadius(selected!),
    enabled: selected !== null,
  })

  const placed = useMemo(() => (map.data ? layout(map.data) : null), [map.data])
  const byId = new Map(placed?.nodes.map((node) => [node.id, node]) ?? [])
  const selectedNode = selected !== null ? byId.get(selected) : undefined
  const edges = map.data?.edges ?? []
  const calls = selected === null ? [] : edges.filter((edge) => edge.from === selected).map((edge) => byId.get(edge.to)!).filter(Boolean)
  const calledBy = selected === null ? [] : edges.filter((edge) => edge.to === selected).map((edge) => byId.get(edge.from)!).filter(Boolean)
  const affected = new Set(view === 'blast' ? (blast.data?.map((service) => service.id) ?? []) : [])
  const neighbours = new Set([...calls, ...calledBy].map((node) => node.id))
  const lit = (id: number) => id === selected || (view === 'blast' ? affected.has(id) : neighbours.has(id))
  const openOf = (name: string) => (incidents.data ? openIncidentsOf(incidents.data, name) : [])
  const owner = services.data?.find((service) => service.id === selected)?.owner.displayName

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Service map"
        description="Every service and what it calls. Choose one to see its links, or what may break if it fails."
      />

      {map.isPending ? (
        <LoadingState message="Loading the service map" />
      ) : map.isError ? (
        <ErrorState message={map.error.message} onRetry={() => map.refetch()} />
      ) : map.data.nodes.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          No services yet. Add services, then their dependencies on the service form.
        </p>
      ) : (
        <div className="flex flex-col gap-5">
          <ViewSwitch view={view} onChange={setView} />

          <section aria-label="Map" className="overflow-hidden rounded-lg border bg-card shadow-elev-1">
            <div className="overflow-x-auto bg-[radial-gradient(var(--border)_1px,transparent_1px)] bg-[size:18px_18px]">
              <div className="relative mx-auto" style={{ width: placed!.width, height: placed!.height }}>
                <svg className="pointer-events-none absolute inset-0 overflow-visible" width={placed!.width} height={placed!.height} aria-hidden="true">
                  <defs>
                    <marker id="map-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                      <path d="M 0 0 L 10 5 L 0 10 z" className="fill-muted-foreground" />
                    </marker>
                  </defs>
                  {edges.map((edge) => {
                    const from = byId.get(edge.from)
                    const to = byId.get(edge.to)
                    if (!from || !to) return null
                    const on = selected !== null && lit(edge.from) && lit(edge.to) && (edge.from === selected || edge.to === selected || view === 'blast')
                    return (
                      <path
                        key={`${edge.from}-${edge.to}`}
                        d={linkPath(from, to)}
                        fill="none"
                        markerEnd="url(#map-arrow)"
                        strokeWidth={on ? 2 : 1.25}
                        className={cn(
                          'stroke-muted-foreground/50 transition-[stroke,opacity]',
                          on && (view === 'blast' ? 'stroke-sev-1' : 'stroke-primary'),
                          selected !== null && !on && 'opacity-30',
                        )}
                      />
                    )
                  })}
                </svg>
                {placed!.nodes.map((node) => {
                  const isSelected = node.id === selected
                  const inBlast = affected.has(node.id)
                  const open = openOf(node.name)
                  const top = open[0]
                  return (
                    <button
                      key={node.id}
                      type="button"
                      aria-pressed={isSelected}
                      aria-label={`${node.name}, ${HEALTH[node.health].label}${inBlast ? ', in the blast radius' : ''}${
                        open.length > 0 ? `, ${open.length} open incident${open.length === 1 ? '' : 's'}` : ''
                      }`}
                      data-affected={inBlast || undefined}
                      onClick={() => setSelected(isSelected ? null : node.id)}
                      className={cn(
                        'absolute flex items-center gap-3 rounded-lg border bg-card px-3 text-left shadow-elev-1 transition-[border-color,opacity,box-shadow] outline-none hover:border-foreground/30 focus-visible:ring-[3px] focus-visible:ring-ring/50',
                        isSelected && (view === 'blast' ? 'border-sev-1 ring-1 ring-sev-1' : 'border-primary ring-1 ring-primary'),
                        inBlast && 'border-sev-1/70 bg-sev-1-bg',
                        selected !== null && !lit(node.id) && 'opacity-40',
                      )}
                      style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                    >
                      <span className={cn('grid size-9 shrink-0 place-items-center rounded-full border-2', HEALTH[node.health].ring)} aria-hidden="true">
                        <Box className="size-4 text-muted-foreground" />
                      </span>
                      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <span className="truncate text-[13px] font-medium">{node.name}</span>
                        <span className="truncate text-[12px] text-muted-foreground capitalize">
                          {HEALTH[node.health].label} · {node.criticality.toLowerCase()}
                        </span>
                      </span>
                      {top && (
                        <span
                          aria-hidden="true"
                          className={cn(
                            'absolute -top-2.5 right-2 rounded-[5px] px-1.5 py-0.5 font-mono text-[11px] leading-none font-semibold',
                            top.severity === 'SEV1'
                              ? 'bg-sev-1-bg text-sev-1-fg shadow-[inset_0_0_0_1px_var(--sev-1-line)]'
                              : top.severity === 'SEV2'
                                ? 'bg-sev-2-bg text-sev-2-fg shadow-[inset_0_0_0_1px_currentColor]'
                                : 'bg-secondary text-muted-foreground',
                          )}
                        >
                          {top.severity}
                          {open.length > 1 ? ` +${open.length - 1}` : ''}
                        </span>
                      )}
                    </button>
                  )
                })}
              </div>
            </div>
            <ul aria-label="Legend" className="flex flex-wrap gap-x-5 gap-y-2 border-t px-[19px] py-3.5 text-[12px] text-muted-foreground">
              {Object.entries(HEALTH).map(([key, health]) => (
                <li key={key} className="flex items-center gap-2">
                  <span className={cn('size-2 rounded-full', health.dot)} aria-hidden="true" />
                  {health.label}
                </li>
              ))}
              <li className="flex items-center gap-2">
                <ArrowRight className="size-3.5" aria-hidden="true" />
                Each link runs from a service to one it depends on
              </li>
            </ul>
          </section>

          <Panel aria-live="polite" aria-label={selectedNode ? selectedNode.name : 'Chosen service'}>
            {!selectedNode ? (
              <p className="px-[19px] py-5 text-[13px] text-muted-foreground">
                Choose a service to see what it calls and what calls it. Switch to Blast radius to see what may break if it fails.
              </p>
            ) : (
              <>
                <div className="flex items-start justify-between gap-3 px-[19px] pt-5">
                  <div className="flex flex-col gap-1">
                    <span className="text-[12px] text-muted-foreground">Service</span>
                    <h2 className="text-[22px] leading-tight font-medium tracking-[-0.025em]">{selectedNode.name}</h2>
                    <span className="mt-1 flex items-center gap-2 text-[13px]">
                      <span className={cn('size-2 rounded-full', HEALTH[selectedNode.health].dot)} aria-hidden="true" />
                      {HEALTH[selectedNode.health].label}
                    </span>
                  </div>
                  <Button variant="ghost" size="icon-sm" aria-label="Clear selection" onClick={() => setSelected(null)}>
                    <X aria-hidden="true" />
                  </Button>
                </div>
                <div className="grid gap-6 px-[19px] py-5 md:grid-cols-3">
                  {view === 'blast' ? (
                    <section className="flex flex-col gap-2.5 md:col-span-2">
                      <h3 className="text-[13px] font-medium">If {selectedNode.name} fails</h3>
                      {blast.isPending ? (
                        <p className="text-[13px] text-muted-foreground">Working out what depends on {selectedNode.name}…</p>
                      ) : blast.isError ? (
                        <p role="alert" className="text-[13px] text-destructive">
                          {blast.error.message}
                        </p>
                      ) : blast.data.length === 0 ? (
                        <p className="text-[13px]">Nothing depends on {selectedNode.name}.</p>
                      ) : (
                        <p className="text-[13px] leading-[1.7]">
                          If {selectedNode.name} fails, {blast.data.length} service{blast.data.length === 1 ? '' : 's'} may be affected:{' '}
                          <strong className="font-medium">{blast.data.map((service) => service.name).join(', ')}</strong>. A dependency is a
                          possible link, not proof of a shared cause.
                        </p>
                      )}
                    </section>
                  ) : (
                    <>
                      <ServiceList title="Calls" items={calls} empty="Nothing: it depends on no other service." />
                      <ServiceList title="Called by" items={calledBy} empty="Nothing depends on it." />
                    </>
                  )}
                  <OpenIncidents incidents={openOf(selectedNode.name)} />
                </div>
                <PanelFooter>
                  <span>{owner ? `Owner · ${owner}` : ''}</span>
                  <Link to={`/services/${selectedNode.id}`} className="flex items-center gap-1.5 hover:text-foreground">
                    Open service
                    <ArrowRight className="size-3.5" aria-hidden="true" />
                  </Link>
                </PanelFooter>
              </>
            )}
          </Panel>

          <ul className="sr-only" aria-label="Dependencies">
            {edges.map((edge) => (
              <li key={`${edge.from}-${edge.to}`}>
                {byId.get(edge.from)?.name} depends on {byId.get(edge.to)?.name}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
