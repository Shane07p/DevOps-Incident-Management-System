import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { getBlastRadius, getServiceMap, type Health, type ServiceMap } from '@/api/serviceMap'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { cn } from '@/lib/utils'

const HEALTH: Record<Health, { label: string; dot: string }> = {
  OPERATIONAL: { label: 'Operational', dot: 'bg-health-operational' },
  DEGRADED: { label: 'Degraded', dot: 'bg-health-degraded' },
  DOWN: { label: 'Down', dot: 'bg-health-down' },
  UNKNOWN: { label: 'Unknown', dot: 'bg-muted-foreground/50' },
}

const NODE_WIDTH = 190
const NODE_HEIGHT = 64
const COLUMN_GAP = 90
const ROW_GAP = 22

type Placed = ServiceMap['nodes'][number] & { x: number; y: number }

/**
 * Columns by depth: a service that needs nothing sits on the left, and each service sits one column
 * right of the deepest service it needs, so every arrow points left, at what it depends on.
 */
function layout(map: ServiceMap): { nodes: Placed[]; width: number; height: number } {
  const needs = new Map<number, number[]>()
  for (const edge of map.edges) needs.set(edge.from, [...(needs.get(edge.from) ?? []), edge.to])
  const depth = new Map<number, number>()
  const depthOf = (id: number, path: Set<number>): number => {
    const known = depth.get(id)
    if (known !== undefined) return known
    // A cycle would never end; its services share a column instead.
    if (path.has(id)) return 0
    path.add(id)
    const deps = needs.get(id) ?? []
    const value = deps.length === 0 ? 0 : 1 + Math.max(...deps.map((dep) => depthOf(dep, path)))
    path.delete(id)
    depth.set(id, value)
    return value
  }
  const columns = new Map<number, ServiceMap['nodes']>()
  for (const node of map.nodes) {
    const column = depthOf(node.id, new Set())
    columns.set(column, [...(columns.get(column) ?? []), node])
  }
  const placed: Placed[] = []
  let rows = 0
  for (const [column, nodes] of columns) {
    nodes
      .toSorted((a, b) => a.name.localeCompare(b.name))
      .forEach((node, row) => {
        placed.push({ ...node, x: column * (NODE_WIDTH + COLUMN_GAP), y: row * (NODE_HEIGHT + ROW_GAP) })
        rows = Math.max(rows, row + 1)
      })
  }
  const columnCount = Math.max(1, ...[...columns.keys()].map((column) => column + 1))
  return {
    nodes: placed,
    width: columnCount * NODE_WIDTH + (columnCount - 1) * COLUMN_GAP,
    height: Math.max(1, rows) * NODE_HEIGHT + Math.max(0, rows - 1) * ROW_GAP,
  }
}

/** Catalog → Service map (US-26): services with their health, who depends on whom, and a chosen service's blast radius. */
export function ServiceMapPage() {
  const map = useQuery({ queryKey: ['service-map'], queryFn: getServiceMap })
  const [selected, setSelected] = useState<number | null>(null)
  const blast = useQuery({
    queryKey: ['blast-radius', selected],
    queryFn: () => getBlastRadius(selected!),
    enabled: selected !== null,
  })

  const placed = useMemo(() => (map.data ? layout(map.data) : null), [map.data])
  const affected = new Set(blast.data?.map((service) => service.id) ?? [])
  const byId = new Map(placed?.nodes.map((node) => [node.id, node]) ?? [])
  const selectedNode = selected !== null ? byId.get(selected) : undefined

  return (
    <div className="mx-auto flex max-w-[1240px] flex-col gap-6">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
          Service map
        </h1>
        <p className="text-muted-foreground">
          Each arrow points from a service to one it depends on. Choose a service to see what may break if it fails.
        </p>
      </div>

      <ul className="flex flex-wrap gap-4 text-sm text-muted-foreground" aria-label="Health">
        {Object.entries(HEALTH).map(([key, health]) => (
          <li key={key} className="flex items-center gap-2">
            <span className={cn('size-2 rounded-full', health.dot)} aria-hidden="true" />
            {health.label}
          </li>
        ))}
      </ul>

      {map.isPending ? (
        <LoadingState message="Loading the service map" />
      ) : map.isError ? (
        <ErrorState message={map.error.message} onRetry={() => map.refetch()} />
      ) : map.data.nodes.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          No services yet. Add services, then their dependencies on the service form.
        </p>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border bg-card p-6">
            <div className="relative" style={{ width: placed!.width, height: placed!.height }}>
              <svg className="pointer-events-none absolute inset-0 overflow-visible" width={placed!.width} height={placed!.height} aria-hidden="true">
                <defs>
                  <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                    <path d="M 0 0 L 10 5 L 0 10 z" className="fill-muted-foreground" />
                  </marker>
                </defs>
                {map.data.edges.map((edge) => {
                  const from = byId.get(edge.from)
                  const to = byId.get(edge.to)
                  if (!from || !to) return null
                  // The links failure travels along: from an affected service to the chosen one, or to another affected one.
                  const lit = affected.has(edge.from) && (edge.to === selected || affected.has(edge.to))
                  return (
                    <line
                      key={`${edge.from}-${edge.to}`}
                      x1={from.x}
                      y1={from.y + NODE_HEIGHT / 2}
                      x2={to.x + NODE_WIDTH}
                      y2={to.y + NODE_HEIGHT / 2}
                      markerEnd="url(#arrow)"
                      className={cn('stroke-muted-foreground/60', lit && 'stroke-destructive')}
                      strokeWidth={lit ? 2 : 1.25}
                    />
                  )
                })}
              </svg>
              {placed!.nodes.map((node) => {
                const isSelected = node.id === selected
                const isAffected = affected.has(node.id)
                return (
                  <button
                    key={node.id}
                    type="button"
                    aria-pressed={isSelected}
                    aria-label={`${node.name}, ${HEALTH[node.health].label}${isAffected ? ', in the blast radius' : ''}`}
                    data-affected={isAffected || undefined}
                    onClick={() => setSelected(isSelected ? null : node.id)}
                    className={cn(
                      'absolute flex flex-col justify-center gap-1 rounded-lg border bg-background px-3 text-left transition-colors hover:border-foreground/40',
                      isSelected && 'border-foreground ring-2 ring-foreground',
                      isAffected && 'border-destructive bg-destructive/10 ring-2 ring-destructive/60',
                      selected !== null && !isSelected && !isAffected && 'opacity-50',
                    )}
                    style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                  >
                    <span className="flex items-center gap-2 font-semibold">
                      <span className={cn('size-2 shrink-0 rounded-full', HEALTH[node.health].dot)} aria-hidden="true" />
                      <span className="truncate">{node.name}</span>
                    </span>
                    <span className="text-xs text-muted-foreground capitalize">
                      {HEALTH[node.health].label} · {node.criticality.toLowerCase()}
                    </span>
                  </button>
                )
              })}
            </div>
          </div>

          <section aria-live="polite" className="rounded-lg border bg-card p-4 text-sm">
            {!selectedNode ? (
              <p className="text-muted-foreground">Choose a service on the map to see its blast radius.</p>
            ) : blast.isPending ? (
              <p className="text-muted-foreground">Working out what depends on {selectedNode.name}…</p>
            ) : blast.isError ? (
              <p role="alert" className="text-destructive">{blast.error.message}</p>
            ) : blast.data.length === 0 ? (
              <p>Nothing depends on {selectedNode.name}.</p>
            ) : (
              <p>
                If {selectedNode.name} fails, {blast.data.length} service{blast.data.length === 1 ? '' : 's'} may be affected:{' '}
                <strong>{blast.data.map((service) => service.name).join(', ')}</strong>. A dependency is a possible link, not
                proof of a shared cause.
              </p>
            )}
          </section>

          <ul className="sr-only" aria-label="Dependencies">
            {map.data.edges.map((edge) => (
              <li key={`${edge.from}-${edge.to}`}>
                {byId.get(edge.from)?.name} depends on {byId.get(edge.to)?.name}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
