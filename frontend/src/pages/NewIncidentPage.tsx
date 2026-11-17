import { useNavigate } from 'react-router-dom'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel } from '@/components/app/Panel'
import { DeclareIncidentForm } from './incident/DeclareIncidentForm'

/** Declares an incident from its own address. The route is for Admins and Engineers; the server checks again. */
export function NewIncidentPage() {
  const navigate = useNavigate()
  return (
    <div className="mx-auto flex max-w-[720px] flex-col">
      <PageHeader
        title="Declare incident"
        description="It starts OPEN, with its response and resolution clocks running from now. The service's targets are copied and stay fixed."
      />
      <Panel className="p-6">
        <DeclareIncidentForm onCancel={() => navigate('/incidents')} />
      </Panel>
    </div>
  )
}
