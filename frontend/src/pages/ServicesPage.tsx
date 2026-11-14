import { useQuery } from '@tanstack/react-query'
import { ChevronRight, Pencil, Plus, Search } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { listIncidents } from '@/api/incidents'
import { getServiceMap } from '@/api/serviceMap'
import { listServices, type Service } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { ServiceFormDialog } from './services/ServiceFormDialog'
import { HEALTH, openIncidentsOf } from './services/serviceHealth'

const statusWord = (status: string) => status.charAt(0) + status.slice(1).toLowerCase()

/**
 * The service catalog as cards: health from the monitors, the most severe open incident, the owner and the
 * criticality. A card opens the service's page. Everyone reads it; only Admins see New and Edit, and the server
 * checks again.
 */
export function ServicesPage() {
  const { user } = useAuth()
  const canConfigure = can(user?.role, 'configure')
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  // Health and open incidents add to a card; the catalog still shows if either fails.
  const map = useQuery({ queryKey: ['service-map'], queryFn: getServiceMap })
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  const [query, setQuery] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Service | null>(null)

  const openForm = (service: Service | null) => {
    setEditing(service)
    setFormOpen(true)
  }

  const health = new Map(map.data?.nodes.map((node) => [node.id, node.health]))
  const shown = services.data?.filter((service) => service.name.toLowerCase().includes(query.trim().toLowerCase())) ?? []

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Services"
        description="Every service, its owner, health and SLA targets."
        actions={
          canConfigure && (
            <Button onClick={() => openForm(null)}>
              <Plus aria-hidden="true" />
              New service
            </Button>
          )
        }
      />

      {services.isPending ? (
        <LoadingState message="Loading services" />
      ) : services.isError ? (
        <ErrorState message={services.error.message} onRetry={() => services.refetch()} />
      ) : services.data.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          {canConfigure ? 'No services yet. Add the first one to start raising incidents.' : 'No services yet. An Admin adds them.'}
        </p>
      ) : (
        <>
          <div className="relative mb-5 w-full sm:w-[220px]">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              id="q"
              type="search"
              aria-label="Find a service"
              placeholder="Find a service…"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="h-[38px] bg-card pl-9"
            />
          </div>
          {shown.length === 0 ? (
            <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">No service matches “{query.trim()}”.</p>
          ) : (
            <ul className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
              {shown.map((service) => {
                const state = HEALTH[health.get(service.id) ?? 'UNKNOWN']
                const open = incidents.data ? openIncidentsOf(incidents.data, service.name) : []
                return (
                  <li
                    key={service.id}
                    className="group relative flex min-w-0 flex-col gap-3.5 rounded-lg border bg-card p-[22px] shadow-elev-1 transition-[border-color,translate] hover:-translate-y-0.5 hover:border-foreground/25 motion-reduce:hover:translate-y-0"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex min-w-0 flex-col gap-1.5">
                        <h2 className="text-[19px] leading-tight font-medium tracking-[-0.025em]">
                          {/* The link covers the card, so the whole card opens the service. */}
                          <Link
                            to={`/services/${service.id}`}
                            className="outline-none after:absolute after:inset-0 after:rounded-lg focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50"
                          >
                            {service.name}
                          </Link>
                        </h2>
                        {service.description && <p className="text-[13px] text-muted-foreground">{service.description}</p>}
                      </div>
                      {canConfigure ? (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          className="relative z-10 -mt-1 -mr-2 text-muted-foreground"
                          aria-label={`Edit ${service.name}`}
                          onClick={() => openForm(service)}
                        >
                          <Pencil aria-hidden="true" />
                        </Button>
                      ) : (
                        <ChevronRight className="mt-0.5 size-[17px] shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
                      )}
                    </div>
                    {map.isSuccess && <Health tone={state.tone} className="text-[13px]">{state.label}</Health>}
                    {incidents.isSuccess && (
                      <p
                        className={cn(
                          'text-[13px]',
                          open.length === 0 ? 'text-muted-foreground' : open[0].severity === 'SEV1' ? 'font-medium text-sev-1-fg' : 'font-medium text-sev-2-fg',
                        )}
                      >
                        {open.length === 0
                          ? 'No open incidents'
                          : `${open[0].reference} · ${open[0].severity} · ${statusWord(open[0].status)}${open.length > 1 ? ` · +${open.length - 1} more` : ''}`}
                      </p>
                    )}
                    <div className="mt-auto flex flex-wrap items-center justify-between gap-2 border-t pt-3.5 text-[13px]">
                      <span className="flex min-w-0 items-center gap-2">
                        <Avatar name={service.owner.displayName} size="sm" />
                        <span className="truncate">{service.owner.displayName}</span>
                      </span>
                      <span className="text-muted-foreground">
                        Criticality: <span className="capitalize">{service.criticality.toLowerCase()}</span>
                      </span>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}

      {canConfigure && <ServiceFormDialog service={editing} open={formOpen} onOpenChange={setFormOpen} />}
    </div>
  )
}
