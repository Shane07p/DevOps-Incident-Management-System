import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { listServices } from '@/api/services'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { ServicePicker } from '@/components/app/ServicePicker'
import { ServiceEscalation } from './escalation/ServiceEscalation'

/** Escalation levels per service. Everyone reads them; only Admins change them, and the server checks again. */
export function EscalationPage() {
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const [params, setParams] = useSearchParams()
  const chosen = services.data?.find((service) => String(service.id) === params.get('service')) ?? services.data?.[0]

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Escalation policies
          </h1>
          <p className="text-muted-foreground">Who is paged next, and when, until someone acknowledges the incident.</p>
        </div>
        {services.data && services.data.length > 0 && (
          <ServicePicker services={services.data} chosen={chosen} onChoose={(id) => setParams({ service: id })} />
        )}
      </div>

      {services.isPending ? (
        <LoadingState message="Loading services" />
      ) : services.isError ? (
        <ErrorState message={services.error.message} onRetry={() => services.refetch()} />
      ) : !chosen ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          No services yet. Escalation levels belong to a service, so an Admin adds services first.
        </p>
      ) : (
        <ServiceEscalation key={chosen.id} serviceId={chosen.id} />
      )}
    </div>
  )
}
