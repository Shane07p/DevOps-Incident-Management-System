import { useQuery } from '@tanstack/react-query'
import { Pencil, Plus } from 'lucide-react'
import { useState } from 'react'
import { listMonitors, type Monitor } from '@/api/monitors'
import { listServices } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { formatIst } from '@/lib/time'
import { MonitorFormDialog } from './monitors/MonitorFormDialog'
import { monitorHealth } from './monitors/monitorHealth'

function interval(seconds: number) {
  if (seconds % 3600 === 0) return `${seconds / 3600} h`
  if (seconds % 60 === 0) return `${seconds / 60} min`
  return `${seconds} s`
}

/** Uptime checks on public URLs (FR-22). Every role reads them; Admins create and change them. */
export function MonitorsPage() {
  const { user } = useAuth()
  const canConfigure = can(user?.role, 'configure')
  const monitors = useQuery({ queryKey: ['monitors'], queryFn: listMonitors })
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Monitor | null>(null)

  const list = monitors.data ?? []
  const selected = list.find((monitor) => monitor.id === selectedId) ?? list[0]

  const openForm = (monitor: Monitor | null) => {
    setEditing(monitor)
    setFormOpen(true)
  }

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Monitors
          </h1>
          <p className="text-muted-foreground">Uptime checks for public URLs. A monitor raises an alert after the failures in a row you set.</p>
        </div>
        {canConfigure && (
          <Button onClick={() => openForm(null)} disabled={!services.data?.length}>
            <Plus aria-hidden="true" />
            New monitor
          </Button>
        )}
      </div>

      {monitors.isPending ? (
        <LoadingState message="Loading monitors" />
      ) : monitors.isError ? (
        <ErrorState message={monitors.error.message} onRetry={() => monitors.refetch()} />
      ) : list.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border p-8 text-center text-muted-foreground">
          No monitors yet.{canConfigure ? ' Create one to start checking a URL.' : ''}
        </p>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full min-w-[560px] text-left text-sm">
              <thead className="border-b border-border text-muted-foreground">
                <tr>
                  <th scope="col" className="px-4 py-3 font-medium">Monitor</th>
                  <th scope="col" className="px-4 py-3 font-medium">Health</th>
                  <th scope="col" className="px-4 py-3 font-medium">Interval</th>
                  <th scope="col" className="px-4 py-3 font-medium">Last checked</th>
                </tr>
              </thead>
              <tbody>
                {list.map((monitor) => {
                  const health = monitorHealth(monitor)
                  const isSelected = selected?.id === monitor.id
                  return (
                    <tr key={monitor.id} aria-selected={isSelected} className={isSelected ? 'bg-accent' : 'hover:bg-muted/50'}>
                      <td className="px-4 py-3">
                        <button type="button" className="text-left font-medium hover:underline" onClick={() => setSelectedId(monitor.id)}>
                          {monitor.name}
                        </button>
                        <div className="max-w-[320px] truncate font-mono text-xs text-muted-foreground">{monitor.url}</div>
                      </td>
                      <td className="px-4 py-3"><Health tone={health.tone}>{health.label}</Health></td>
                      <td className="px-4 py-3 font-mono">{interval(monitor.intervalSeconds)}</td>
                      <td className="px-4 py-3 font-mono text-muted-foreground">
                        {monitor.lastCheckedAt ? formatIst(monitor.lastCheckedAt) : 'Not yet'}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {selected && (
            <section aria-label={`${selected.name} details`} className="flex flex-col gap-4 rounded-lg border border-border p-5">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <h2 className="font-semibold">{selected.name}</h2>
                  <p className="text-sm text-muted-foreground">{selected.serviceName}</p>
                </div>
                {canConfigure && (
                  <Button size="sm" variant="outline" onClick={() => openForm(selected)} aria-label={`Edit ${selected.name}`}>
                    <Pencil aria-hidden="true" />
                    Edit
                  </Button>
                )}
              </div>
              <Health tone={monitorHealth(selected).tone}>{monitorHealth(selected).label}</Health>
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
                <dt className="text-muted-foreground">Expects</dt>
                <dd className="font-mono">HTTP {selected.expectedStatus}</dd>
                <dt className="text-muted-foreground">Alerts after</dt>
                <dd>{selected.failureThreshold} failures in a row</dd>
                <dt className="text-muted-foreground">Next check</dt>
                <dd className="font-mono">{selected.nextCheckAt ? formatIst(selected.nextCheckAt) : '—'}</dd>
              </dl>
              <p className="text-xs text-muted-foreground">All times in IST.</p>
            </section>
          )}
        </div>
      )}

      {canConfigure && (
        <MonitorFormDialog open={formOpen} onOpenChange={setFormOpen} monitor={editing} services={services.data ?? []} />
      )}
    </div>
  )
}
