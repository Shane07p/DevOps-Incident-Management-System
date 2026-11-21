import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, ExternalLink } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { ApiRequestError } from '@/api/client'
import { getPublicStatus, type PublicStatus } from '@/api/publicStatus'
import { listServices, updateService, type Service } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel } from '@/components/app/Panel'
import { PublicUpdateBadge } from '@/components/app/PublicUpdateBadge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { publicStatusSummary } from '@/lib/publicStatus'
import { formatIst } from '@/lib/time'
import { toRequest, valuesFrom } from './services/serviceForm'

function LiveSummary({ data }: { data: PublicStatus }) {
  const summary = publicStatusSummary(data)
  return (
    <div className="flex min-w-0 flex-col gap-4 border-t px-[19px] py-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Health tone={data.services.length ? summary.tone : 'neutral'}>
          {data.services.length ? summary.title : 'No services shown'}
        </Health>
        <span className="text-[13px] text-muted-foreground">
          {data.services.length} {data.services.length === 1 ? 'service' : 'services'} shown
        </span>
      </div>
      {data.activeIncidents.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">No active public incidents.</p>
      ) : (
        <ul aria-label="Active public incidents" className="flex min-w-0 flex-col gap-3">
          {data.activeIncidents.map((incident) => {
            const latest = incident.updates[0]
            return (
              <li key={incident.id} className="flex min-w-0 flex-col gap-1.5 border-t pt-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="min-w-0 font-medium break-words [overflow-wrap:anywhere]">{incident.serviceName}</span>
                  <PublicUpdateBadge status={incident.status} />
                </div>
                {latest && (
                  <>
                    <p className="text-[13px] break-words whitespace-pre-line [overflow-wrap:anywhere]">{latest.message}</p>
                    <time dateTime={latest.createdAt} className="font-mono text-xs text-muted-foreground">{formatIst(latest.createdAt)}</time>
                  </>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

function ServicePublication({ service, slug }: { service: Service; slug: string }) {
  const client = useQueryClient()
  const [name, setName] = useState(service.publicName || service.name)
  const publicName = name.trim() || service.name
  const save = useMutation({
    mutationFn: (visible: boolean) => updateService(service.id, {
      ...toRequest(valuesFrom(service)),
      publicVisible: visible,
      publicName,
    }),
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ['services'] }),
        client.invalidateQueries({ queryKey: ['public-status', slug] }),
      ])
      toast.success(`${service.name}'s status page settings are saved.`)
    },
  })
  const error = save.error instanceof ApiRequestError ? save.error.errors.publicName ?? save.error.message : save.error?.message
  const errorId = `publication-error-${service.id}`
  return (
    <form
      aria-label={`Status page settings for ${service.name}`}
      onSubmit={(event) => { event.preventDefault(); save.mutate(service.publicVisible ?? false) }}
      className="grid min-w-0 gap-3 sm:grid-cols-[auto_minmax(0,1fr)_auto] sm:items-end"
    >
      <div className="flex items-center gap-2 sm:pb-2">
        <Switch
          id={`publicVisible-${service.id}`}
          checked={service.publicVisible ?? false}
          onCheckedChange={(visible) => save.mutate(visible)}
          disabled={save.isPending}
          aria-label={`Shown: ${service.name}`}
          aria-describedby={error ? errorId : undefined}
        />
        <Label htmlFor={`publicVisible-${service.id}`}>Shown</Label>
      </div>
      <div className="flex min-w-0 flex-col gap-1.5">
        <Label htmlFor={`publicName-${service.id}`}>Public name</Label>
        <Input
          id={`publicName-${service.id}`}
          name="publicName"
          aria-label={`Public name for ${service.name}`}
          value={name}
          maxLength={100}
          onChange={(event) => setName(event.target.value)}
          disabled={save.isPending}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? errorId : undefined}
        />
      </div>
      <Button type="submit" variant="outline" disabled={save.isPending || publicName === (service.publicName || service.name)} aria-label={`Save public name for ${service.name}`}>
        {save.isPending ? 'Saving…' : 'Save'}
      </Button>
      {error && <p id={errorId} role="alert" className="text-sm text-destructive sm:col-span-3">{error}</p>}
    </form>
  )
}

/** The org's public status page, managed through the existing service catalog API (FR-21, D45). */
export function StatusPagesPage() {
  const { org, user } = useAuth()
  const canConfigure = can(user?.role, 'configure')
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState(false)
  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  const status = useQuery({
    queryKey: ['public-status', org?.slug],
    queryFn: () => getPublicStatus(org!.slug),
    enabled: Boolean(org),
    refetchInterval: 30_000,
    retry: false,
  })
  if (!org) return <LoadingState message="Loading your status page" />
  const url = `${window.location.origin}/status/${encodeURIComponent(org.slug)}`
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      setCopyError(false)
    } catch {
      setCopyError(true)
    }
  }
  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader title="Status pages" description="What customers see during an incident" />
      <div className="flex min-w-0 flex-col gap-6">
        <Panel title="Your status page">
          <div className="flex min-w-0 flex-wrap items-center gap-3 px-[19px] pb-4">
            <code className="min-w-0 flex-1 basis-full font-mono text-[13px] break-all select-all sm:basis-auto">{url}</code>
            <Button variant="outline" onClick={copy}><Copy aria-hidden="true" />{copied ? 'Copied' : 'Copy'}</Button>
            <Button variant="outline" asChild><a href={url} target="_blank" rel="noopener noreferrer"><ExternalLink aria-hidden="true" />Open</a></Button>
            {copyError && <p role="alert" className="basis-full text-sm text-destructive">Could not copy the URL. Select it and copy it manually.</p>}
          </div>
          {status.isPending ? <LoadingState message="Loading public status" /> : status.isError ? (
            <ErrorState title="Public status could not be loaded" message={status.error.message} onRetry={() => status.refetch()} />
          ) : <LiveSummary data={status.data} />}
        </Panel>

        <Panel title="Services on the page">
          {services.isPending ? <LoadingState message="Loading services" /> : services.isError ? (
            <ErrorState title="Services could not be loaded" message={services.error.message} onRetry={() => services.refetch()} />
          ) : services.data.length === 0 ? (
            <div className="flex flex-col gap-2 px-[19px] pb-[19px] text-[13px]">
              <p className="font-medium">No services yet</p>
              <Link to="/services" className="w-fit text-primary underline">Go to services</Link>
            </div>
          ) : (
            <>
              {!services.data.some((service) => service.publicVisible) && (
                <div className="flex flex-col gap-1 px-[19px] pb-4 text-[13px]">
                  <p className="font-medium">Your status page lists no services</p>
                  <p className="text-muted-foreground">{canConfigure ? 'Turn on Shown for a service below to put it on your page.' : 'Ask an Admin to turn on a service for your page.'}</p>
                </div>
              )}
              <ul className="min-w-0">
                {services.data.map((service) => (
                  <li key={service.id} className="flex min-w-0 flex-col gap-3 border-t px-[19px] py-4">
                    <h3 className="text-[14px] font-medium break-words [overflow-wrap:anywhere]">{service.name}</h3>
                    {canConfigure ? (
                      <ServicePublication key={`${service.id}:${service.publicVisible}:${service.publicName}`} service={service} slug={org.slug} />
                    ) : (
                      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2 text-[13px]">
                        <span className="min-w-0 break-words [overflow-wrap:anywhere]">Public name: {service.publicName || service.name}</span>
                        <Health tone={service.publicVisible ? 'ok' : 'neutral'}>{service.publicVisible ? 'Shown' : 'Hidden'}</Health>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            </>
          )}
        </Panel>
        <p className="text-[13px] text-muted-foreground">Posting updates: public updates are posted from an <Link to="/incidents" className="text-primary underline">incident&apos;s page</Link>.</p>
      </div>
    </div>
  )
}
