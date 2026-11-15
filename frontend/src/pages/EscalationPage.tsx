import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { listServices } from '@/api/services'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { ServicePicker } from '@/components/app/ServicePicker'
import { ServiceEscalation } from './escalation/ServiceEscalation'

/** Escalation levels per service. Everyone reads them; only Admins change them, and the server checks again. */
export function EscalationPage() {
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const [params, setParams] = useSearchParams()
  const chosen = services.data?.find((service) => String(service.id) === params.get('service')) ?? services.data?.[0]

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Escalation policies"
        description="Who gets paged next when nobody acknowledges, and after how long."
        actions={
          services.data &&
          services.data.length > 0 && <ServicePicker services={services.data} chosen={chosen} onChoose={(id) => setParams({ service: id })} />
        }
      />

      {services.isPending ? (
        <LoadingState message="Loading services" />
      ) : services.isError ? (
        <ErrorState message={services.error.message} onRetry={() => services.refetch()} />
      ) : !chosen ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          No services yet. Escalation levels belong to a service, so an Admin adds services first.
        </p>
      ) : (
        <ServiceEscalation key={chosen.id} service={chosen} />
      )}
    </div>
  )
}
