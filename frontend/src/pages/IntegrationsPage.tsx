import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, CircleX, Unplug } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import {
  deleteTelemetryConnection,
  getTelemetryConnection,
  saveTelemetryConnection,
  testTelemetryConnection,
  type TelemetryConnection,
  type TelemetryTest,
} from '@/api/telemetryConnection'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { formatIst } from '@/lib/time'

// Prometheus's own name rules; the server checks them again.
const LABEL_NAME = /^[a-zA-Z_][a-zA-Z0-9_]*$/
const METRIC_NAME = /^[a-zA-Z_:][a-zA-Z0-9_:]*$/

const url = z.string().trim().max(500, { error: 'Use at most 500 characters' })
  .refine((value) => value === '' || /^https?:\/\/[^\s/]+/i.test(value), { error: 'Enter a URL that starts with https://' })
const user = z.string().trim().max(100, { error: 'Use at most 100 characters' })
  .refine((value) => !value.includes(':'), { error: 'A user name has no colon' })
const name = (rule: RegExp, max: number) => z.string().trim().max(max, { error: `Use at most ${max} characters` })
  .refine((value) => value === '' || rule.test(value), { error: 'Letters, digits, and _ only, not starting with a digit' })

const grafanaSchema = z.object({
  prometheusUrl: url,
  prometheusUser: user,
  lokiUrl: url,
  lokiUser: user,
  token: z.string().max(2000, { error: 'Use at most 2000 characters' }),
  serviceLabel: name(LABEL_NAME, 100),
  requestsMetric: name(METRIC_NAME, 200),
  statusLabel: name(LABEL_NAME, 100),
  durationMetric: name(METRIC_NAME, 200),
}).refine((values) => values.prometheusUrl !== '' || values.lokiUrl !== '', {
  path: ['prometheusUrl'],
  error: 'Enter the Prometheus URL, the Loki URL, or both',
})

type GrafanaValues = z.infer<typeof grafanaSchema>

const FIELDS: (keyof GrafanaValues)[] = [
  'prometheusUrl', 'prometheusUser', 'lokiUrl', 'lokiUser', 'token',
  'serviceLabel', 'requestsMetric', 'statusLabel', 'durationMetric',
]

function valuesFrom(connection: TelemetryConnection): GrafanaValues {
  return {
    prometheusUrl: connection.prometheusUrl ?? '',
    prometheusUser: connection.prometheusUser ?? '',
    lokiUrl: connection.lokiUrl ?? '',
    lokiUser: connection.lokiUser ?? '',
    // The saved token never comes back; empty keeps it.
    token: '',
    serviceLabel: connection.serviceLabel,
    requestsMetric: connection.requestsMetric,
    statusLabel: connection.statusLabel,
    durationMetric: connection.durationMetric,
  }
}

/**
 * Admins connect the org's own Grafana here (D49): logs, metrics, and incident telemetry read only from it.
 * The route is Admin-only, and the server checks every request again.
 */
export function IntegrationsPage() {
  const connection = useQuery({ queryKey: ['telemetry-connection'], queryFn: getTelemetryConnection })

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
          Integrations
        </h1>
        <p className="text-muted-foreground">
          Connect your own Grafana. Settle reads your logs and metrics from it, and never from anyone else's.
        </p>
      </div>
      {connection.isPending ? (
        <p className="text-muted-foreground">Loading the connection…</p>
      ) : connection.isError ? (
        <p role="alert" className="text-destructive">{connection.error.message}</p>
      ) : (
        <GrafanaCard connection={connection.data} />
      )}
    </div>
  )
}

function GrafanaCard({ connection }: { connection: TelemetryConnection }) {
  const queryClient = useQueryClient()
  const form = useForm<GrafanaValues>({ resolver: zodResolver(grafanaSchema), defaultValues: valuesFrom(connection) })
  const [result, setResult] = useState<TelemetryTest | null>(null)
  const [disconnecting, setDisconnecting] = useState(false)

  // A saved or deleted connection comes back from the server; show it, with the token field empty again.
  useEffect(() => {
    form.reset(valuesFrom(connection))
  }, [connection, form])

  const onSubmit = form.handleSubmit(async (values) => {
    if (!connection.tokenSaved && values.token.trim() === '') {
      form.setError('token', { message: 'Enter the read-only token' })
      return
    }
    try {
      const saved = await saveTelemetryConnection(values)
      queryClient.setQueryData(['telemetry-connection'], saved)
      setResult(null)
      toast.success('Grafana is connected.')
    } catch (error) {
      if (error instanceof ApiRequestError && Object.keys(error.errors).length > 0) {
        for (const [field, message] of Object.entries(error.errors)) {
          form.setError(FIELDS.includes(field as keyof GrafanaValues) ? (field as keyof GrafanaValues) : 'root', { message })
        }
      } else {
        form.setError('root', { message: error instanceof Error ? error.message : 'The connection was not saved.' })
      }
    }
  })

  const test = useMutation({
    mutationFn: testTelemetryConnection,
    onSuccess: setResult,
    onError: (error) => toast.error(error.message),
  })

  const disconnect = useMutation({
    mutationFn: deleteTelemetryConnection,
    onSuccess: () => {
      setDisconnecting(false)
      setResult(null)
      toast.success('Grafana is disconnected. Logs and metrics are off until you connect it again.')
      return queryClient.invalidateQueries({ queryKey: ['telemetry-connection'] })
    },
    onError: (error) => toast.error(error.message),
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>Grafana</CardTitle>
        <CardDescription>
          {connection.connected
            ? `Connected${connection.updatedBy ? ` by ${connection.updatedBy}` : ''}${connection.updatedAt ? `, ${formatIst(connection.updatedAt)}` : ''}.`
            : 'Not connected. Logs and metrics stay off until an Admin connects your Grafana.'}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Form {...form}>
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-6">
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField form={form} name="prometheusUrl" label="Prometheus URL" placeholder="https://prometheus-prod-XX.grafana.net/api/prom" />
              <TextField form={form} name="prometheusUser" label="Prometheus user" description="The instance ID, if your Grafana asks for one." />
              <TextField form={form} name="lokiUrl" label="Loki URL" placeholder="https://logs-prod-XXX.grafana.net" />
              <TextField form={form} name="lokiUser" label="Loki user" description="The instance ID, if your Grafana asks for one." />
            </div>
            <TextField
              form={form}
              name="token"
              label="Read-only token"
              type="password"
              placeholder={connection.tokenSaved ? 'Saved. Leave empty to keep it.' : ''}
              description="Use a token that can only read metrics and logs. It is stored encrypted and never shown again."
            />
            <fieldset className="flex flex-col gap-3">
              <legend className="mb-2 text-sm font-medium">How your telemetry is named</legend>
              <div className="grid gap-4 sm:grid-cols-2">
                <TextField form={form} name="serviceLabel" label="Service label" description="The label that says which service a log line or metric belongs to." />
                <TextField form={form} name="statusLabel" label="Status label" description="The label that holds the HTTP status code." />
                <TextField form={form} name="requestsMetric" label="Request counter" description="Counts requests; error rate is its 5xx share." />
                <TextField form={form} name="durationMetric" label="Duration histogram" description="Request duration in seconds; latency is read from its buckets." />
              </div>
            </fieldset>
            {form.formState.errors.root && (
              <p role="alert" className="text-sm text-destructive">{form.formState.errors.root.message}</p>
            )}
            {result && (
              <ul aria-label="Connection test" className="flex flex-col gap-1 text-sm">
                <TestLine store="Prometheus" outcome={result.prometheus} />
                <TestLine store="Loki" outcome={result.loki} />
              </ul>
            )}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" disabled={form.formState.isSubmitting}>Save</Button>
              {connection.connected && (
                <>
                  <Button type="button" variant="outline" disabled={test.isPending} onClick={() => test.mutate()}>
                    Test connection
                  </Button>
                  <Button type="button" variant="ghost" onClick={() => setDisconnecting(true)}>
                    <Unplug aria-hidden="true" />
                    Disconnect
                  </Button>
                </>
              )}
            </div>
          </form>
        </Form>
      </CardContent>
      <Dialog open={disconnecting} onOpenChange={setDisconnecting}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Disconnect Grafana?</DialogTitle>
            <DialogDescription>
              Logs, metrics, and incident telemetry stop until an Admin connects Grafana again. The saved token is deleted.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDisconnecting(false)}>Keep it</Button>
            <Button variant="destructive" disabled={disconnect.isPending} onClick={() => disconnect.mutate()}>
              Disconnect
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

function TestLine({ store, outcome }: { store: string; outcome: string }) {
  const ok = outcome === 'ok'
  return (
    <li className="flex items-center gap-2">
      {ok ? <CircleCheck aria-hidden="true" className="size-4 text-status-resolved" /> : <CircleX aria-hidden="true" className="size-4 text-destructive" />}
      <span className="font-medium">{store}:</span>
      <span className={ok ? '' : 'text-muted-foreground'}>{ok ? 'works' : outcome}</span>
    </li>
  )
}

function TextField({
  form,
  name,
  label,
  placeholder,
  description,
  type = 'text',
}: {
  form: ReturnType<typeof useForm<GrafanaValues>>
  name: keyof GrafanaValues
  label: string
  placeholder?: string
  description?: string
  type?: string
}) {
  return (
    <FormField
      control={form.control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input type={type} placeholder={placeholder} autoComplete="off" {...field} />
          </FormControl>
          {description && <FormDescription>{description}</FormDescription>}
          <FormMessage />
        </FormItem>
      )}
    />
  )
}
