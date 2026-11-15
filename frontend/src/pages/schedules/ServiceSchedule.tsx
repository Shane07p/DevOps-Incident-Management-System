import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Trash2, TriangleAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiRequestError } from '@/api/client'
import { getOnCall, updateOnCall, type OnCallScheduleRequest, type Shift } from '@/api/onCall'
import { listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { formatIst, formatIstClock, istDayStart } from '@/lib/time'
import { AddShiftForm } from './AddShiftForm'
import { coverage, nextShift, onCallAt, toShiftRequest } from './schedule'
import { ShiftTimeline } from './ShiftTimeline'

/** The current time, refreshed every minute so "on call now" moves on by itself. */
function useNow() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000)
    return () => clearInterval(timer)
  }, [])
  return now
}

const DAY_MS = 86_400_000

const errorMessage = (error: unknown) =>
  error instanceof ApiRequestError && Object.keys(error.errors).length > 0
    ? Object.values(error.errors).join(' ')
    : error instanceof Error
      ? error.message
      : 'The schedule was not saved. Try again.'

/** One service's on-call: who is on now, who is next, the fallback contact, and the shifts. */
export function ServiceSchedule({ serviceId }: { serviceId: number }) {
  const { user } = useAuth()
  const isAdmin = can(user?.role, 'configure')
  const queryClient = useQueryClient()
  const now = useNow()
  const schedule = useQuery({ queryKey: ['on-call', serviceId], queryFn: () => getOnCall(serviceId) })
  // Responders and fallback contacts are users; that list is Admin-only, like editing.
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: isAdmin })
  const [fallbackChoice, setFallbackChoice] = useState('')
  const [fallbackError, setFallbackError] = useState<string | null>(null)
  const [removing, setRemoving] = useState<Shift | null>(null)

  // The server replaces every shift on each save, so every change sends the whole schedule. One save at
  // a time: a second change started meanwhile would send the schedule from before the first, and undo
  // it when it lands (NFR-11.2). Every action waits until the saved schedule is back.
  const save = useMutation({
    mutationFn: (request: OnCallScheduleRequest) => updateOnCall(serviceId, request),
    onSuccess: (saved) => queryClient.setQueryData(['on-call', serviceId], saved),
  })

  if (schedule.isPending) return <LoadingState message="Loading the schedule" />
  if (schedule.isError) return <ErrorState message={schedule.error.message} onRetry={() => schedule.refetch()} />

  const data = schedule.data
  const current = onCallAt(data, now)
  const next = nextShift(data, now)
  const shifts = data.shifts.toSorted((a, b) => Date.parse(a.startTime) - Date.parse(b.startTime))

  const saveFallback = async () => {
    setFallbackError(null)
    try {
      await save.mutateAsync({ fallbackContactId: Number(fallbackChoice), shifts: data.shifts.map(toShiftRequest) })
      toast.success('Fallback contact saved.')
      setFallbackChoice('')
    } catch (error) {
      setFallbackError(errorMessage(error))
    }
  }

  const removeShift = async (shift: Shift) => {
    try {
      await save.mutateAsync({
        // A saved schedule always has a fallback contact; shifts can't exist without one.
        fallbackContactId: data.fallbackContactId!,
        shifts: data.shifts.filter((other) => other.id !== shift.id).map(toShiftRequest),
      })
      toast.success(`Removed ${shift.responderName}'s shift.`)
    } catch (error) {
      toast.error(errorMessage(error))
    }
    setRemoving(null)
  }

  const fallbackName = data.fallbackContactName
  const dayStart = istDayStart(now)
  const gaps = coverage(data.shifts, dayStart, dayStart + DAY_MS).filter((part) => part.kind === 'gap')
  const shiftStatus = (shift: Shift) =>
    Date.parse(shift.endTime) <= now ? 'Ended' : Date.parse(shift.startTime) <= now ? 'On call now' : 'Upcoming'

  return (
    <div className="flex flex-col gap-5">
      {gaps.length > 0 && (
        <p className="flex items-start gap-3 rounded-lg border border-sev-2/40 bg-sev-2-bg px-4 py-3.5 text-[13px] text-foreground">
          <TriangleAlert className="mt-0.5 size-4 shrink-0 text-sev-2-fg" aria-hidden="true" />
          <span>
            <strong className="font-medium">{gaps.length === 1 ? '1 gap today' : `${gaps.length} gaps today`}</strong>
            {' · '}
            {gaps.map((gap) => `${formatIstClock(gap.start)}–${formatIstClock(gap.end, dayStart + DAY_MS)}`).join(' and ')}.{' '}
            {fallbackName ? `${fallbackName}, the fallback, gets new incidents then.` : 'Nobody gets new incidents then: set a fallback contact.'}
          </span>
        </p>
      )}

      <div className="grid gap-5 md:grid-cols-2">
        <Panel title="On call now">
          <div className="flex items-center gap-3 px-[19px] pb-5">
            {current.kind === 'nobody' ? (
              <p className="text-[13px] text-muted-foreground">Nobody: no shift and no fallback contact.</p>
            ) : (
              <>
                <Avatar name={current.name} />
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <strong className="text-[14px] font-medium">{current.name}</strong>
                  <span className="text-[13px] text-muted-foreground">
                    {current.kind === 'shift' ? `Until ${formatIst(current.until)}` : 'Fallback contact: no shift covers this time'}
                  </span>
                </div>
                <Health tone={current.kind === 'shift' ? 'ok' : 'warn'} className="shrink-0 text-[12px]">
                  {current.kind === 'shift' ? 'On shift' : 'Fallback'}
                </Health>
              </>
            )}
          </div>
        </Panel>
        <Panel title="Next">
          <div className="flex items-center gap-3 px-[19px] pb-5">
            {next ? (
              <>
                <Avatar name={next.responderName} />
                <div className="flex min-w-0 flex-col gap-1">
                  <strong className="text-[14px] font-medium">{next.responderName}</strong>
                  <span className="text-[13px] text-muted-foreground">From {formatIst(next.startTime)}</span>
                </div>
              </>
            ) : (
              <p className="text-[13px] text-muted-foreground">No shift planned after this one.</p>
            )}
          </div>
        </Panel>
      </div>

      <Panel title="Today’s coverage" action={<span className="text-[12px] text-muted-foreground">IST (UTC+05:30)</span>}>
        <ShiftTimeline shifts={data.shifts} now={now} fallbackName={fallbackName} />
        {shifts.length === 0 ? (
          <p className="border-t px-[19px] py-6 text-center text-[13px] text-muted-foreground">No shifts yet.</p>
        ) : (
          <div className="overflow-x-auto border-t">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-[19px]">Responder</TableHead>
                  <TableHead>Starts</TableHead>
                  <TableHead>Ends</TableHead>
                  <TableHead className={cn(!isAdmin && 'pr-[19px]')}>Status</TableHead>
                  {isAdmin && <TableHead className="w-[110px] pr-[19px]" aria-label="Actions" />}
                </TableRow>
              </TableHeader>
              <TableBody>
                {shifts.map((shift) => {
                  const status = shiftStatus(shift)
                  return (
                    <TableRow key={shift.id} className={cn(status === 'Ended' && 'text-muted-foreground')}>
                      <TableCell className="pl-[19px]">
                        <span className="flex items-center gap-2 font-medium">
                          <Avatar name={shift.responderName} size="sm" />
                          {shift.responderName}
                        </span>
                      </TableCell>
                      <TableCell className="font-mono text-[12px] tnum">{formatIst(shift.startTime)}</TableCell>
                      <TableCell className="font-mono text-[12px] tnum">{formatIst(shift.endTime)}</TableCell>
                      <TableCell className={cn('text-[13px]', !isAdmin && 'pr-[19px]')}>
                        {status === 'On call now' ? (
                          <Health tone="ok" className="text-[13px]">
                            On call now
                          </Health>
                        ) : (
                          status
                        )}
                      </TableCell>
                      {isAdmin && (
                        <TableCell className="pr-[19px] text-right">
                          <Button
                            variant="ghost"
                            size="sm"
                            aria-label={`Remove ${shift.responderName}'s shift from ${formatIst(shift.startTime)}`}
                            onClick={() => setRemoving(shift)}
                            disabled={save.isPending}
                          >
                            <Trash2 aria-hidden="true" />
                            Remove
                          </Button>
                        </TableCell>
                      )}
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        )}
        <PanelFooter>
          <span>
            Fallback contact: <span className="text-foreground">{fallbackName ?? 'not set'}</span>. Gets new incidents when no shift
            covers the time.
          </span>
        </PanelFooter>
      </Panel>

      {isAdmin && (
        <div className="grid items-start gap-5 lg:grid-cols-[1fr_2fr]">
          <div className="flex flex-col gap-3 rounded-lg border bg-card p-[19px] shadow-elev-1">
            <h3 className="text-[13px] font-medium">Fallback contact</h3>
            <div className="flex flex-col gap-2">
              <Label htmlFor="fallbackContactId">Contact</Label>
              <Select value={fallbackChoice} onValueChange={setFallbackChoice} disabled={!users.data}>
                <SelectTrigger id="fallbackContactId" className="w-full" aria-describedby="fallbackContactId-message">
                  <SelectValue placeholder={data.fallbackContactName ?? 'Choose a contact'} />
                </SelectTrigger>
                <SelectContent>
                  {users.data?.map((option) => (
                    <SelectItem key={option.id} value={String(option.id)}>
                      {option.displayName} ({option.email})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {fallbackError && (
                <p id="fallbackContactId-message" role="alert" className="text-sm text-destructive">
                  {fallbackError}
                </p>
              )}
            </div>
            <Button className="self-end" onClick={saveFallback} disabled={!fallbackChoice || save.isPending}>
              Save fallback
            </Button>
          </div>
          {data.fallbackContactId === null ? (
            <p className="rounded-lg border bg-card p-[19px] text-[13px] text-muted-foreground shadow-elev-1">
              Set a fallback contact first. New incidents go to them whenever no shift covers the time.
            </p>
          ) : (
            <AddShiftForm
              schedule={data}
              users={users.data ?? []}
              saving={save.isPending}
              onAdd={(shift) =>
                save
                  .mutateAsync({
                    fallbackContactId: data.fallbackContactId!,
                    shifts: [...data.shifts.map(toShiftRequest), shift],
                  })
                  .then(() => toast.success('Shift added.'))
              }
            />
          )}
        </div>
      )}

      <Dialog open={removing !== null} onOpenChange={(open) => !open && setRemoving(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove this shift?</DialogTitle>
            <DialogDescription>
              {removing &&
                `${removing.responderName}, ${formatIst(removing.startTime)} to ${formatIst(removing.endTime)}. Incidents already assigned keep their assignee.`}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRemoving(null)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={() => removing && removeShift(removing)} disabled={save.isPending}>
              Remove shift
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
