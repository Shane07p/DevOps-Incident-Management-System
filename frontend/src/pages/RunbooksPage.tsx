
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiRequestError } from '@/api/client'
import {
  createRunbook,
  deleteRunbook,
  listRunbooks,
  updateRunbook,
  type Runbook,
} from '@/api/runbooks'
import { listServices } from '@/api/services'
import { useAuth } from '@/auth/AuthProvider'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'

export function RunbooksPage() {
  const { user } = useAuth()
  const canEdit = user?.role === 'ADMIN' || user?.role === 'ENGINEER'
  const queryClient = useQueryClient()

  const services = useQuery({
    queryKey: ['services'],
    queryFn: listServices,
  })

  const [selectedServiceId, setSelectedServiceId] = useState<number | null>(null)
  const [editing, setEditing] = useState<Runbook | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [steps, setSteps] = useState<string[]>([''])
  const [formError, setFormError] = useState('')
  const [deleting, setDeleting] = useState<Runbook | null>(null)

  const serviceId = selectedServiceId ?? services.data?.[0]?.id ?? null

  const runbooks = useQuery({
    queryKey: ['runbooks', serviceId],
    queryFn: () => listRunbooks(serviceId!),
    enabled: serviceId !== null,
  })

  const saveMutation = useMutation({
    mutationFn: () => {
      const payload = {
        title: title.trim(),
        steps: steps.map((step) => step.trim()),
      }

      if (editing) {
        return updateRunbook(editing.id, payload)
      }

      return createRunbook(serviceId!, payload)
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ['runbooks', serviceId],
      })
      setFormOpen(false)
      setEditing(null)
    },
  })

  const removeMutation = useMutation({
    mutationFn: deleteRunbook,
    onSuccess: () => {
      setDeleting(null)
      void queryClient.invalidateQueries({
        queryKey: ['runbooks', serviceId],
      })
    },
  })

  function openForm(runbook: Runbook | null) {
    saveMutation.reset()
    setEditing(runbook)
    setTitle(runbook?.title ?? '')
    setSteps(runbook ? [...runbook.steps] : [''])
    setFormError('')
    setFormOpen(true)
  }

  function updateStep(index: number, value: string) {
    setSteps((current) =>
      current.map((step, i) => (i === index ? value : step)),
    )
  }

  function moveStep(index: number, direction: -1 | 1) {
    const nextIndex = index + direction

    setSteps((current) => {
      if (nextIndex < 0 || nextIndex >= current.length) {
        return current
      }

      const updated = [...current]
      const first = updated[index]
      updated[index] = updated[nextIndex]
      updated[nextIndex] = first
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

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div>
        <h1 className="text-4xl font-bold">Runbooks</h1>
        <p className="text-muted-foreground">
          Step-by-step instructions for fixing service incidents.
        </p>
      </div>

      {services.isPending ? (
        <LoadingState message="Loading services" />
      ) : services.isError ? (
        <ErrorState
          message={services.error.message}
          onRetry={() => void services.refetch()}
        />
      ) : services.data.length === 0 ? (
        <p>No services available.</p>
      ) : (
        <>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <label className="flex flex-col gap-2">
              <span className="font-medium">Service</span>
              <select
                aria-label="Select service"
                className="rounded-md border bg-background p-2"
                value={serviceId ?? ''}
                onChange={(event) => {
                  setSelectedServiceId(Number(event.target.value))
                  setFormOpen(false)
                }}
              >
                {services.data.map((service) => (
                  <option key={service.id} value={service.id}>
                    {service.name}
                  </option>
                ))}
              </select>
            </label>

            {canEdit && (
              <Button onClick={() => openForm(null)}>
                New runbook
              </Button>
            )}
          </div>

          {runbooks.isPending ? (
            <LoadingState message="Loading runbooks" />
          ) : runbooks.isError ? (
            <ErrorState
              message={runbooks.error.message}
              onRetry={() => void runbooks.refetch()}
            />
          ) : runbooks.data.length === 0 ? (
            <p className="rounded-lg border p-6 text-muted-foreground">
              No runbooks for this service yet.
            </p>
          ) : (
            <div className="grid gap-4">
              {runbooks.data.map((runbook) => (
                <section
                  key={runbook.id}
                  className="rounded-lg border bg-card p-5"
                >
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <h2 className="text-xl font-semibold">
                      {runbook.title}
                    </h2>

                    {canEdit && (
                      <div className="flex gap-2">
                        <Button
                          variant="outline"
                          onClick={() => openForm(runbook)}
                        >
                          Edit
                        </Button>
                        <Button
                          variant="destructive"
                          disabled={removeMutation.isPending}
                          onClick={() => {
                            removeMutation.reset()
                            setDeleting(runbook)
                          }}
                        >
                          Delete
                        </Button>
                      </div>
                    )}
                  </div>

                  <ol className="mt-4 list-decimal space-y-2 pl-6">
                    {runbook.steps.map((step, index) => (
                      <li key={index}>{step}</li>
                    ))}
                  </ol>
                </section>
              ))}
            </div>
          )}

          {canEdit && formOpen && (
            <section className="rounded-lg border bg-card p-5">
              <h2 className="mb-4 text-xl font-semibold">
                {editing ? 'Edit runbook' : 'Create runbook'}
              </h2>

              <label className="mb-4 flex flex-col gap-2">
                <span>Title</span>
                <input
                  id="title"
                  aria-label="Runbook title"
                  className="rounded-md border bg-background p-2"
                  value={title}
                  maxLength={200}
                  onChange={(event) => setTitle(event.target.value)}
                />
              </label>

              <h3 className="mb-2 font-semibold">Steps</h3>

              <div className="space-y-3">
                {steps.map((step, index) => (
                  <div
                    key={index}
                    className="flex flex-wrap items-center gap-2"
                  >
                    <span className="w-6">{index + 1}.</span>
                    <input
                      id={`steps-${index}`}
                      aria-label={`Step ${index + 1}`}
                      className="min-w-0 flex-1 rounded-md border bg-background p-2"
                      value={step}
                      maxLength={2000}
                      onChange={(event) =>
                        updateStep(index, event.target.value)
                      }
                    />
                    <Button
                      variant="outline"
                      disabled={index === 0}
                      onClick={() => moveStep(index, -1)}
                    >
                      Up
                    </Button>
                    <Button
                      variant="outline"
                      disabled={index === steps.length - 1}
                      onClick={() => moveStep(index, 1)}
                    >
                      Down
                    </Button>
                    <Button
                      variant="outline"
                      disabled={steps.length === 1}
                      onClick={() =>
                        setSteps((current) =>
                          current.filter((_, i) => i !== index),
                        )
                      }
                    >
                      Remove
                    </Button>
                  </div>
                ))}
              </div>

              <Button
                className="mt-4"
                variant="outline"
                disabled={steps.length >= 50}
                onClick={() =>
                  setSteps((current) => [...current, ''])
                }
              >
                Add step
              </Button>

              {steps.length >= 50 && (
                <p className="mt-2 text-muted-foreground">A runbook can have at most 50 steps.</p>
              )}

              {formError && (
                <p role="alert" className="mt-3 text-destructive">
                  {formError}
                </p>
              )}

              {saveMutation.isError && (
                <p role="alert" className="mt-3 text-destructive">
                  {saveMutation.error instanceof ApiRequestError && Object.keys(saveMutation.error.errors).length > 0
                    ? Object.values(saveMutation.error.errors).join(' ')
                    : saveMutation.error.message}
                </p>
              )}

              <div className="mt-5 flex gap-3">
                <Button
                  disabled={saveMutation.isPending}
                  onClick={save}
                >
                  {saveMutation.isPending
                    ? 'Saving...'
                    : 'Save runbook'}
                </Button>
                <Button
                  variant="outline"
                  onClick={() => setFormOpen(false)}
                >
                  Cancel
                </Button>
              </div>
            </section>
          )}
        </>
      )}
      <Dialog open={deleting !== null} onOpenChange={(open) => { if (!open) setDeleting(null) }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete {deleting?.title}?</DialogTitle>
            <DialogDescription>This permanently deletes the runbook and its steps.</DialogDescription>
          </DialogHeader>
          {removeMutation.isError && (
            <p role="alert" className="text-destructive">{removeMutation.error.message}</p>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleting(null)}>Cancel</Button>
            <Button variant="destructive" disabled={removeMutation.isPending} onClick={() => deleting && removeMutation.mutate(deleting.id)}>
              Delete runbook
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
