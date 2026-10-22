import { useQuery } from '@tanstack/react-query'
import { Pencil, Plus } from 'lucide-react'
import { useState } from 'react'
import { listServices, type Service } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { CriticalityBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ServiceFormDialog } from './services/ServiceFormDialog'

/** The service catalog. Everyone reads it; only Admins see New and Edit, and the server checks again. */
export function ServicesPage() {
  const { user } = useAuth()
  const canConfigure = can(user?.role, 'configure')
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Service | null>(null)

  const openForm = (service: Service | null) => {
    setEditing(service)
    setFormOpen(true)
  }

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Services
          </h1>
          <p className="text-muted-foreground">Who owns each service, how critical it is, and its response and resolution targets.</p>
        </div>
        {canConfigure && (
          <Button onClick={() => openForm(null)}>
            <Plus aria-hidden="true" />
            New service
          </Button>
        )}
      </div>

      {services.isPending ? (
        <LoadingState message="Loading services" />
      ) : services.isError ? (
        <ErrorState message={services.error.message} onRetry={() => services.refetch()} />
      ) : services.data.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          {canConfigure ? 'No services yet. Add the first one to start raising incidents.' : 'No services yet. An Admin adds them.'}
        </p>
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Owner</TableHead>
                <TableHead className="w-[140px]">Criticality</TableHead>
                {canConfigure && <TableHead className="w-[100px]" aria-label="Actions" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {services.data.map((service) => (
                <TableRow key={service.id}>
                  <TableCell>
                    <div className="flex flex-col gap-0.5">
                      <span className="font-semibold">{service.name}</span>
                      {service.description && (
                        <span className="max-w-[480px] truncate text-xs text-muted-foreground">{service.description}</span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>{service.owner.displayName}</TableCell>
                  <TableCell>
                    <CriticalityBadge criticality={service.criticality} />
                  </TableCell>
                  {canConfigure && (
                    <TableCell className="text-right">
                      <Button variant="ghost" size="sm" aria-label={`Edit ${service.name}`} onClick={() => openForm(service)}>
                        <Pencil aria-hidden="true" />
                        Edit
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {canConfigure && <ServiceFormDialog service={editing} open={formOpen} onOpenChange={setFormOpen} />}
    </div>
  )
}
