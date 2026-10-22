import { useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { Link } from 'react-router-dom'
import { listIncidents } from '@/api/incidents'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { SeverityBadge, StatusBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatIst } from '@/lib/time'

/** Every incident, newest first. Viewers read; Engineers and Admins also declare. */
export function IncidentsPage() {
  const { user } = useAuth()
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })
  // The server already sends newest first; sorting again keeps the page right if that ever changes.
  const newestFirst = incidents.data?.toSorted((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Incidents
          </h1>
          <p className="text-muted-foreground">Active and past incidents, newest first. Times are IST.</p>
        </div>
        {can(user?.role, 'respond') && (
          <Button asChild>
            <Link to="/incidents/new">
              <Plus aria-hidden="true" />
              Declare incident
            </Link>
          </Button>
        )}
      </div>

      {incidents.isPending ? (
        <LoadingState message="Loading incidents" />
      ) : incidents.isError ? (
        <ErrorState message={incidents.error.message} onRetry={() => incidents.refetch()} />
      ) : newestFirst?.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">No incidents yet.</p>
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[110px]">Reference</TableHead>
                <TableHead>Title</TableHead>
                <TableHead>Service</TableHead>
                <TableHead className="w-[90px]">Severity</TableHead>
                <TableHead className="w-[150px]">Status</TableHead>
                <TableHead className="w-[150px]">Declared</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {newestFirst?.map((incident) => (
                <TableRow key={incident.id}>
                  <TableCell className="font-mono text-xs text-muted-foreground">{incident.reference}</TableCell>
                  <TableCell>
                    <Link to={`/incidents/${incident.id}`} className="font-semibold hover:underline">
                      {incident.title}
                    </Link>
                  </TableCell>
                  <TableCell>{incident.serviceName}</TableCell>
                  <TableCell>
                    <SeverityBadge severity={incident.severity} />
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={incident.status} />
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(incident.createdAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}
