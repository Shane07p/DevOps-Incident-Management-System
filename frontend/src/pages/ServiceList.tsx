import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { SEVERITIES, listServices } from '../api/services'
import { formatMinutes } from './serviceFormRules'

export default function ServiceList({ canEdit }: { canEdit: boolean }) {
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-xl font-semibold">Services</h2>
        {canEdit && (
          <Link to="/services/new" className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white">
            New service
          </Link>
        )}
      </div>

      {services.isPending && <p>Loading services…</p>}
      {services.isError && <p role="alert">Could not load services: {services.error.message}</p>}
      {services.data?.length === 0 && <p>No services registered yet.</p>}

      {services.data && services.data.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b">
                <th className="py-2 pr-4">Name</th>
                <th className="py-2 pr-4">Owner</th>
                <th className="py-2 pr-4">Criticality</th>
                {SEVERITIES.map((severity) => (
                  <th key={severity} className="py-2 pr-4">
                    {severity} respond / resolve
                  </th>
                ))}
                {canEdit && <th className="py-2" />}
              </tr>
            </thead>
            <tbody>
              {services.data.map((service) => (
                <tr key={service.id} className="border-b">
                  <td className="py-2 pr-4 font-medium">{service.name}</td>
                  <td className="py-2 pr-4">{service.owner.displayName}</td>
                  <td className="py-2 pr-4">{service.criticality}</td>
                  {SEVERITIES.map((severity) => (
                    <td key={severity} className="py-2 pr-4">
                      {formatMinutes(service.targets[severity].responseMinutes)} /{' '}
                      {formatMinutes(service.targets[severity].resolutionMinutes)}
                    </td>
                  ))}
                  {canEdit && (
                    <td className="py-2">
                      <Link
                        to={`/services/${service.id}/edit`}
                        aria-label={`Edit ${service.name}`}
                        className="text-blue-600"
                      >
                        Edit
                      </Link>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
