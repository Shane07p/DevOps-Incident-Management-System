
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import {
  createMonitor,
  listMonitors,
  updateMonitor,
  type Monitor,
  type MonitorRequest,
} from '@/api/monitors'
import { listServices } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'

const emptyForm = {
  name: '',
  url: '',
  expectedStatus: '200',
  intervalSeconds: '60',
  serviceId: '',
  failureThreshold: '3',
  enabled: true,
}

type FormValues = typeof emptyForm

export function MonitorsPage() {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const canConfigure = can(user?.role, 'configure')

  const monitors = useQuery({
    queryKey: ['monitors'],
    queryFn: listMonitors,
  })

  const services = useQuery({
    queryKey: ['services'],
    queryFn: listServices,
  })

  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Monitor | null>(null)
  const [values, setValues] = useState<FormValues>(emptyForm)
  const [formError, setFormError] = useState('')

  useEffect(() => {
    if (!open) return

    setFormError('')
    setValues(
      editing
        ? {
            name: editing.name,
            url: editing.url,
            expectedStatus: String(editing.expectedStatus),
            intervalSeconds: String(editing.intervalSeconds),
            serviceId: String(editing.serviceId),
            failureThreshold: String(editing.failureThreshold),
            enabled: editing.enabled,
          }
        : {
            ...emptyForm,
            serviceId: services.data?.[0]
              ? String(services.data[0].id)
              : '',
          },
    )
  }, [open, editing, services.data])

  const save = useMutation({
    mutationFn: (request: MonitorRequest) =>
      editing
        ? updateMonitor(editing.id, request)
        : createMonitor(request),
    onSuccess: async () => {
      toast.success(editing ? 'Monitor updated.' : 'Monitor created.')
      setOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['monitors'] })
    },
    onError: (error: Error) => {
      setFormError(error.message)
    },
  })

  function startCreate() {
    setEditing(null)
    setOpen(true)
  }

  function startEdit(monitor: Monitor) {
    setEditing(monitor)
    setOpen(true)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError('')

    const request: MonitorRequest = {
      name: values.name.trim(),
      url: values.url.trim(),
      expectedStatus: Number(values.expectedStatus),
      intervalSeconds: Number(values.intervalSeconds),
      serviceId: Number(values.serviceId),
      failureThreshold: Number(values.failureThreshold),
      enabled: values.enabled,
    }

    if (!request.name || !request.url || !request.serviceId) {
      setFormError('Please complete all required fields.')
      return
    }

    if (
      !Number.isInteger(request.expectedStatus) ||
      request.expectedStatus < 100 ||
      request.expectedStatus > 599
    ) {
      setFormError('Expected status must be between 100 and 599.')
      return
    }

    if (
      !Number.isInteger(request.intervalSeconds) ||
      request.intervalSeconds < 1 ||
      request.intervalSeconds > 86400
    ) {
      setFormError('Interval must be between 1 and 86400 seconds.')
      return
    }

    if (
      !Number.isInteger(request.failureThreshold) ||
      request.failureThreshold < 1 ||
      request.failureThreshold > 100
    ) {
      setFormError('Failure threshold must be between 1 and 100.')
      return
    }

    try {
      const parsedUrl = new URL(request.url)
      if (!['http:', 'https:'].includes(parsedUrl.protocol)) {
        setFormError('Enter a valid HTTP or HTTPS URL.')
        return
      }
    } catch {
      setFormError('Enter a valid HTTP or HTTPS URL.')
      return
    }

    save.mutate(request)
  }

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-2">
          <h1 className="text-[44px] leading-[0.9] font-[750]">
            Monitors
          </h1>
          <p className="text-muted-foreground">
            Monitor website availability, status codes and uptime.
          </p>
        </div>

        {canConfigure && (
          <Button onClick={startCreate}>
            <Plus className="mr-2 size-4" />
            Create monitor
          </Button>
        )}
      </div>

      {monitors.isPending ? (
        <LoadingState message="Loading monitors" />
      ) : monitors.isError ? (
        <ErrorState
          message={monitors.error.message}
          onRetry={() => monitors.refetch()}
        />
      ) : monitors.data.length === 0 ? (
        <div className="rounded-lg border bg-card p-10 text-center">
          <h2 className="font-semibold">No monitors yet</h2>
          <p className="mt-2 text-sm text-muted-foreground">
            Create your first monitor to start tracking availability.
          </p>
          {canConfigure && (
            <Button className="mt-4" onClick={startCreate}>
              <Plus className="mr-2 size-4" />
              Create monitor
            </Button>
          )}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border bg-card">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/40 text-left">
              <tr>
                <th className="p-4">Monitor</th>
                <th className="p-4">Status</th>
                <th className="p-4">Interval</th>
                <th className="p-4">Uptime history</th>
                {canConfigure && <th className="p-4">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {monitors.data.map((monitor) => {
                const down =
                  monitor.enabled &&
                  monitor.consecutiveFailures >= monitor.failureThreshold
                const degraded =
                  monitor.enabled &&
                  monitor.consecutiveFailures > 0 &&
                  !down

                const status = !monitor.enabled
                  ? 'Paused'
                  : down
                    ? 'Down'
                    : degraded
                      ? 'Degraded'
                      : 'Operational'

                return (
                  <tr key={monitor.id} className="border-b last:border-0">
                    <td className="p-4">
                      <div className="font-semibold">{monitor.name}</div>
                      <a
                        href={monitor.url}
                        target="_blank"
                        rel="noreferrer"
                        className="text-xs text-muted-foreground hover:underline"
                      >
                        {monitor.url}
                      </a>
                      <div className="mt-1 text-xs text-muted-foreground">
                        {monitor.serviceName}
                      </div>
                    </td>

                    <td className="p-4">
                      <span
                        className={
                          down
                            ? 'font-medium text-red-600'
                            : degraded
                              ? 'font-medium text-amber-600'
                              : !monitor.enabled
                                ? 'text-muted-foreground'
                                : 'font-medium text-green-600'
                        }
                      >
                        ● {status}
                      </span>
                      {monitor.consecutiveFailures > 0 && (
                        <div className="mt-1 text-xs text-muted-foreground">
                          {monitor.consecutiveFailures} consecutive failures
                        </div>
                      )}
                    </td>

                    <td className="p-4">{monitor.intervalSeconds}s</td>

                    <td className="min-w-[220px] p-4">
                      <div
                        className="flex gap-1"
                        aria-label="Illustrative uptime bars; historical data unavailable"
                      >
                        {Array.from({ length: 30 }, (_, index) => (
                          <span
                            key={index}
                            className={`h-7 flex-1 rounded-sm ${
                              down && index === 29
                                ? 'bg-red-500'
                                : degraded && index === 29
                                  ? 'bg-amber-400'
                                  : 'bg-green-500'
                            }`}
                          />
                        ))}
                      </div>
                      <p className="mt-1 text-[10px] text-muted-foreground">
                        Illustrative bars; historical check data is not
                        available from the API.
                      </p>
                    </td>

                    {canConfigure && (
                      <td className="p-4">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => startEdit(monitor)}
                          aria-label={`Edit ${monitor.name}`}
                        >
                          <Pencil className="mr-1 size-3" />
                          Edit
                        </Button>
                      </td>
                    )}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {editing ? `Edit ${editing.name}` : 'Create monitor'}
            </DialogTitle>
            <DialogDescription>
              Configure the URL and how often it should be checked.
            </DialogDescription>
          </DialogHeader>

          <form onSubmit={submit} className="flex flex-col gap-4">
            <label className="flex flex-col gap-1 text-sm font-medium">
              Monitor name
              <Input
                required
                maxLength={100}
                value={values.name}
                onChange={(e) =>
                  setValues({ ...values, name: e.target.value })
                }
                placeholder="Website monitor"
              />
            </label>

            <label className="flex flex-col gap-1 text-sm font-medium">
              URL
              <Input
                required
                type="url"
                value={values.url}
                onChange={(e) =>
                  setValues({ ...values, url: e.target.value })
                }
                placeholder="https://example.com"
              />
            </label>

            <label className="flex flex-col gap-1 text-sm font-medium">
              Service
              <select
                required
                value={values.serviceId}
                onChange={(e) =>
                  setValues({ ...values, serviceId: e.target.value })
                }
                className="h-10 rounded-md border bg-background px-3"
              >
                <option value="">Select service</option>
                {services.data?.map((service) => (
                  <option key={service.id} value={service.id}>
                    {service.name}
                  </option>
                ))}
              </select>
              {services.isError && (
                <span className="text-xs text-destructive">
                  Could not load services: {services.error.message}
                </span>
              )}
            </label>

            <div className="grid grid-cols-2 gap-3">
              <label className="flex flex-col gap-1 text-sm font-medium">
                Expected HTTP status
                <Input
                  type="number"
                  min="100"
                  max="599"
                  required
                  value={values.expectedStatus}
                  onChange={(e) =>
                    setValues({ ...values, expectedStatus: e.target.value })
                  }
                />
              </label>

              <label className="flex flex-col gap-1 text-sm font-medium">
                Interval (seconds)
                <Input
                  type="number"
                  min="1"
                  max="86400"
                  required
                  value={values.intervalSeconds}
                  onChange={(e) =>
                    setValues({ ...values, intervalSeconds: e.target.value })
                  }
                />
              </label>
            </div>

            <label className="flex flex-col gap-1 text-sm font-medium">
              Failure threshold
              <Input
                type="number"
                min="1"
                max="100"
                required
                value={values.failureThreshold}
                onChange={(e) =>
                  setValues({ ...values, failureThreshold: e.target.value })
                }
              />
            </label>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={values.enabled}
                onChange={(e) =>
                  setValues({ ...values, enabled: e.target.checked })
                }
              />
              Enable this monitor
            </label>

            {formError && (
              <p role="alert" className="text-sm text-destructive">
                {formError}
              </p>
            )}

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setOpen(false)}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={
                  save.isPending ||
                  services.isPending ||
                  !services.data?.length
                }
              >
                {save.isPending
                  ? 'Saving...'
                  : editing
                    ? 'Save changes'
                    : 'Create monitor'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}
