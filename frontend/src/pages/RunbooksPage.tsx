import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowRight, ArrowUp, BookOpen, ChevronRight, Pencil, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { listIncidents } from '@/api/incidents'
import { createRunbook, deleteRunbook, listRunbooks, updateRunbook, type Runbook } from '@/api/runbooks'
import { listServices, type Service } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel } from '@/components/app/Panel'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { openIncidentsOf } from './services/serviceHealth'

interface Entry {
  runbook: Runbook
  service: Service
}

/**
 * Every runbook in the org, listed by service, with the chosen one's steps beside the list. Engineers and Admins
 * write and change them in the same place; Viewers read them. The chosen runbook is in the address (?runbook=).
 */
export function RunbooksPage() {
  const { user } = useAuth()
  const canEdit = user?.role === 'ADMIN' || user?.role === 'ENGINEER'
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()

  const services = useQuery({ queryKey: ['services'], queryFn: listServices })
  // One list per service, under the same key the incident page reads, so either page warms the other.
  const runbookLists = useQueries({
    queries: (services.data ?? []).map((service) => ({
      queryKey: ['runbooks', service.id],
      queryFn: () => listRunbooks(service.id),
    })),
  })
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: listIncidents })

  const [editing, setEditing] = useState<Runbook | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [formServiceId, setFormServiceId] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [steps, setSteps] = useState<string[]>([''])
  const [formError, setFormError] = useState('')
  const [deleting, setDeleting] = useState<Entry | null>(null)

  const entries: Entry[] = (services.data ?? []).flatMap(
    (service, index) => runbookLists[index]?.data?.map((runbook) => ({ runbook, service })) ?? [],
  )
  const listsPending = runbookLists.some((list) => list.isPending)
  const failed = runbookLists.find((list) => list.isError)
  const chosenId = Number(params.get('runbook'))
  const selected = entries.find((entry) => entry.runbook.id === chosenId) ?? entries[0]

  const choose = (id: number) => {
    setFormOpen(false)
    setParams((current) => {
      const next = new URLSearchParams(current)
      next.set('runbook', String(id))
      return next
    })
  }

  const saveMutation = useMutation({
    mutationFn: () => {
      const payload = { title: title.trim(), steps: steps.map((step) => step.trim()) }
      if (editing) return updateRunbook(editing.id, payload)
      return createRunbook(formServiceId!, payload)
    },
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['runbooks', formServiceId] })
      setFormOpen(false)
      setEditing(null)
      choose(saved.id)
    },
  })

  const removeMutation = useMutation({
    mutationFn: (entry: Entry) => deleteRunbook(entry.runbook.id),
    onSuccess: (_, entry) => {
      setDeleting(null)
      setParams((current) => {
        const next = new URLSearchParams(current)
        next.delete('runbook')
        return next
      })
      void queryClient.invalidateQueries({ queryKey: ['runbooks', entry.service.id] })
    },
  })

  function openForm(entry: Entry | null) {
    saveMutation.reset()
    setEditing(entry?.runbook ?? null)
    setFormServiceId(entry?.service.id ?? selected?.service.id ?? services.data?.[0]?.id ?? null)
    setTitle(entry?.runbook.title ?? '')
    setSteps(entry ? [...entry.runbook.steps] : [''])
    setFormError('')
    setFormOpen(true)
  }

  function updateStep(index: number, value: string) {
    setSteps((current) => current.map((step, i) => (i === index ? value : step)))
  }

  function moveStep(index: number, direction: -1 | 1) {
    const nextIndex = index + direction
    setSteps((current) => {
      if (nextIndex < 0 || nextIndex >= current.length) return current
      const updated = [...current]
      ;[updated[index], updated[nextIndex]] = [updated[nextIndex], updated[index]]
      return updated
    })
  }

  function save() {
    if (!title.trim()) {
      setFormError('Enter a runbook title.')
      return
    }
    if (steps.length === 0 || steps.some((step) => !step.trim())) {
      setFormError('Add at least one non-empty step.')
      return
    }
    if (title.length > 200) {
      setFormError('A runbook title can have at most 200 characters.')
      return
    }
    if (steps.length > 50 || steps.some((step) => step.length > 2000)) {
      setFormError('Use at most 50 steps, with at most 2,000 characters each.')
      return
    }
    setFormError('')
    saveMutation.mutate()
  }

  const form = (
    <Panel title={editing ? 'Edit runbook' : 'New runbook'}>
      <div className="flex flex-col gap-5 px-[19px] pb-5">
        {!editing && services.data && (
          <div className="flex flex-col gap-2">
            <Label htmlFor="serviceId">Service</Label>
            <Select value={String(formServiceId ?? '')} onValueChange={(value) => setFormServiceId(Number(value))}>
              <SelectTrigger id="serviceId" className="w-full sm:w-[280px]">
                <SelectValue placeholder="Choose a service" />
              </SelectTrigger>
              <SelectContent>
                {services.data.map((service) => (
                  <SelectItem key={service.id} value={String(service.id)}>
                    {service.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
        <div className="flex flex-col gap-2">
          <Label htmlFor="title">Title</Label>
          <Input id="title" aria-label="Runbook title" value={title} maxLength={200} onChange={(event) => setTitle(event.target.value)} />
        </div>
        <fieldset className="flex flex-col gap-2.5">
          <legend className="mb-2 text-sm font-medium">Steps</legend>
          {steps.map((step, index) => (
            <div key={index} className="flex items-center gap-2">
              <span className="grid size-[26px] shrink-0 place-items-center rounded-full border font-mono text-[12px] text-muted-foreground">
                {index + 1}
              </span>
              <Input
                id={`steps-${index}`}
                aria-label={`Step ${index + 1}`}
                className="min-w-0 flex-1"
                value={step}
                maxLength={2000}
                onChange={(event) => updateStep(index, event.target.value)}
              />
              <Button variant="ghost" size="icon-sm" aria-label="Up" title="Move up" disabled={index === 0} onClick={() => moveStep(index, -1)}>
                <ArrowUp aria-hidden="true" />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label="Down"
                title="Move down"
                disabled={index === steps.length - 1}
                onClick={() => moveStep(index, 1)}
              >
                <ArrowDown aria-hidden="true" />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label="Remove"
                title="Remove step"
                disabled={steps.length === 1}
                onClick={() => setSteps((current) => current.filter((_, i) => i !== index))}
              >
                <Trash2 aria-hidden="true" />
              </Button>
            </div>
          ))}
        </fieldset>
        <div className="flex flex-col gap-2">
          <Button className="w-fit" variant="outline" disabled={steps.length >= 50} onClick={() => setSteps((current) => [...current, ''])}>
            <Plus aria-hidden="true" />
            Add step
          </Button>
          {steps.length >= 50 && <p className="text-[13px] text-muted-foreground">A runbook can have at most 50 steps.</p>}
        </div>
        {formError && (
          <p role="alert" className="text-[13px] text-destructive">
            {formError}
          </p>
        )}
        {saveMutation.isError && (
          <p role="alert" className="text-[13px] text-destructive">
            {saveMutation.error instanceof ApiRequestError && Object.keys(saveMutation.error.errors).length > 0
              ? Object.values(saveMutation.error.errors).join(' ')
              : saveMutation.error.message}
          </p>
        )}
        <div className="flex gap-2.5">
          <Button disabled={saveMutation.isPending} onClick={save}>
            {saveMutation.isPending ? 'Saving…' : 'Save runbook'}
          </Button>
          <Button variant="outline" onClick={() => setFormOpen(false)}>
            Cancel
          </Button>
        </div>
      </div>
    </Panel>
  )

  const openIncident = selected && incidents.data ? openIncidentsOf(incidents.data, selected.service.name)[0] : undefined

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Runbooks"
        description="Step-by-step fixes, linked to services."
        actions={
          canEdit &&
          services.isSuccess &&
          services.data.length > 0 && (
            <Button onClick={() => openForm(null)}>
              <Plus aria-hidden="true" />
              New runbook
            </Button>
          )
        }
      />

      {services.isPending || (services.isSuccess && listsPending) ? (
        <LoadingState message="Loading runbooks" />
      ) : services.isError ? (
        <ErrorState message={services.error.message} onRetry={() => void services.refetch()} />
      ) : failed?.error ? (
        <ErrorState message={failed.error.message} onRetry={() => void failed.refetch()} />
      ) : services.data.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">No services yet, so no runbooks.</p>
      ) : (
        <div className="grid items-start gap-5 lg:grid-cols-[260px_minmax(0,1fr)]">
          {entries.length === 0 ? (
            <p className="rounded-lg border bg-card p-6 text-[13px] text-muted-foreground lg:col-span-2">
              No runbooks yet.{canEdit && ' Write the first one with New runbook.'}
            </p>
          ) : (
            <nav aria-label="Runbooks">
              <ul className="flex flex-col gap-1">
                {entries.map((entry) => {
                  const current = entry === selected && !(formOpen && !editing)
                  return (
                    <li key={entry.runbook.id}>
                      <button
                        type="button"
                        aria-current={current ? 'true' : undefined}
                        onClick={() => choose(entry.runbook.id)}
                        className={cn(
                          'flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left transition-colors outline-none hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50',
                          current && 'bg-accent',
                        )}
                      >
                        <BookOpen className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                          <span className={cn('truncate text-[13px]', current && 'font-medium text-primary')}>{entry.runbook.title}</span>
                          <span className="truncate text-[12px] text-muted-foreground">{entry.service.name}</span>
                        </span>
                        <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                      </button>
                    </li>
                  )
                })}
              </ul>
            </nav>
          )}

          {canEdit && formOpen ? (
            form
          ) : (
            selected && (
              <Panel aria-label={selected.runbook.title}>
                <div className="flex flex-col gap-5 p-[22px]">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="flex min-w-0 flex-col gap-1">
                      <Link to={`/services/${selected.service.id}`} className="w-fit text-[12px] text-muted-foreground hover:text-foreground">
                        {selected.service.name}
                      </Link>
                      <h2 className="text-[22px] leading-tight font-medium tracking-[-0.025em]">{selected.runbook.title}</h2>
                    </div>
                    {canEdit && (
                      <div className="flex gap-2">
                        <Button variant="outline" size="sm" onClick={() => openForm(selected)}>
                          <Pencil aria-hidden="true" />
                          Edit
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          className="text-destructive"
                          disabled={removeMutation.isPending}
                          onClick={() => {
                            removeMutation.reset()
                            setDeleting(selected)
                          }}
                        >
                          <Trash2 aria-hidden="true" />
                          Delete
                        </Button>
                      </div>
                    )}
                  </div>
                  <div className="flex flex-col gap-3">
                    <h3 className="text-[15px] font-medium">Steps</h3>
                    <ol className="flex flex-col gap-3">
                      {selected.runbook.steps.map((step, index) => (
                        <li key={index} className="flex items-start gap-3 text-[13px] leading-[1.6]">
                          <span
                            aria-hidden="true"
                            className="grid size-[26px] shrink-0 place-items-center rounded-full border font-mono text-[12px] text-muted-foreground"
                          >
                            {index + 1}
                          </span>
                          <span className="pt-[3px]">{step}</span>
                        </li>
                      ))}
                    </ol>
                  </div>
                  {openIncident && (
                    <Button asChild className="w-fit">
                      <Link to={`/incidents/${openIncident.id}`}>
                        Open {openIncident.reference}
                        <ArrowRight aria-hidden="true" />
                      </Link>
                    </Button>
                  )}
                </div>
              </Panel>
            )
          )}
        </div>
      )}

      <Dialog
        open={deleting !== null}
        onOpenChange={(open) => {
          if (!open) setDeleting(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete {deleting?.runbook.title}?</DialogTitle>
            <DialogDescription>This permanently deletes the runbook and its steps.</DialogDescription>
          </DialogHeader>
          {removeMutation.isError && (
            <p role="alert" className="text-destructive">
              {removeMutation.error.message}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button variant="destructive" disabled={removeMutation.isPending} onClick={() => deleting && removeMutation.mutate(deleting)}>
              Delete runbook
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
