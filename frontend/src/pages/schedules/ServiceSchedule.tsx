import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Trash2 } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { ApiRequestError } from '@/api/client'
import { getOnCall, updateOnCall, type OnCallScheduleRequest, type Shift } from '@/api/onCall'
import { listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { formatIst } from '@/lib/time'
import { AddShiftForm } from './AddShiftForm'
import { nextShift, onCallAt, toShiftRequest } from './schedule'
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

const errorMessage = (error: unknown) =>
  error instanceof ApiRequestError && Object.keys(error.errors).length > 0
    ? Object.values(error.errors).join(' ')
    : error instanceof Error
      ? error.message
      : 'The schedule was not saved. Try again.'

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border bg-card p-4">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <div className="flex flex-col gap-0.5">{children}</div>
    </div>
  )
}

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

  // The server replaces every shift on each save, so every change sends the whole schedule.
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
      toast.success(`Removed ${shift.responderUsername}'s shift.`)
    } catch (error) {
      toast.error(errorMessage(error))
    }
    setRemoving(null)
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="grid gap-4 sm:grid-cols-3">
        <Fact label="On call now">
          {current.kind === 'shift' ? (
            <>
              <span className="text-lg font-semibold">{current.username}</span>
              <span className="text-xs text-muted-foreground">Until {formatIst(current.until)}</span>
            </>
          ) : current.kind === 'fallback' ? (
            <>
              <span className="text-lg font-semibold">{current.username}</span>
              <span className="text-xs text-muted-foreground">Fallback contact: no shift right now</span>
            </>
          ) : (
            <span className="text-lg font-semibold text-muted-foreground">Nobody</span>
          )}
        </Fact>
        <Fact label="Next shift">
          {next ? (
            <>
              <span className="text-lg font-semibold">{next.responderUsername}</span>
              <span className="text-xs text-muted-foreground">From {formatIst(next.startTime)}</span>
            </>
          ) : (
            <span className="text-lg font-semibold text-muted-foreground">None planned</span>
          )}
        </Fact>
        <Fact label="Fallback contact">
          <span className="text-lg font-semibold">{data.fallbackContactUsername ?? 'Not set'}</span>
          <span className="text-xs text-muted-foreground">Gets new incidents when no shift covers the time</span>
        </Fact>
      </div>

      <ShiftTimeline shifts={data.shifts} now={now} />

      {shifts.length === 0 ? (
        <p className="rounded-lg border bg-card p-6 text-center text-muted-foreground">No shifts yet.</p>
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Responder</TableHead>
                <TableHead>Starts</TableHead>
                <TableHead>Ends</TableHead>
                {isAdmin && <TableHead className="w-[110px]" aria-label="Actions" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {shifts.map((shift) => {
                const past = Date.parse(shift.endTime) <= now
                return (
                  <TableRow key={shift.id} className={cn(past && 'text-muted-foreground')}>
                    <TableCell className="font-semibold">{shift.responderUsername}</TableCell>
                    <TableCell className="font-mono text-xs tnum">{formatIst(shift.startTime)}</TableCell>
                    <TableCell className="font-mono text-xs tnum">{formatIst(shift.endTime)}</TableCell>
                    {isAdmin && (
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          aria-label={`Remove ${shift.responderUsername}'s shift from ${formatIst(shift.startTime)}`}
                          onClick={() => setRemoving(shift)}
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

      {isAdmin && (
        <div className="grid gap-4 lg:grid-cols-[1fr_2fr]">
          <div className="flex flex-col gap-3 rounded-lg border bg-card p-4">
            <h3 className="font-semibold">Fallback contact</h3>
            <div className="flex flex-col gap-2">
              <Label htmlFor="fallbackContactId">Contact</Label>
              <Select value={fallbackChoice} onValueChange={setFallbackChoice} disabled={!users.data}>
                <SelectTrigger id="fallbackContactId" className="w-full" aria-describedby="fallbackContactId-message">
                  <SelectValue placeholder={data.fallbackContactUsername ?? 'Choose a contact'} />
                </SelectTrigger>
                <SelectContent>
                  {users.data?.map((option) => (
                    <SelectItem key={option.id} value={String(option.id)}>
                      {option.displayName} ({option.username})
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
            <p className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
              Set a fallback contact first. New incidents go to them whenever no shift covers the time.
            </p>
          ) : (
            <AddShiftForm
              schedule={data}
              users={users.data ?? []}
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
                `${removing.responderUsername}, ${formatIst(removing.startTime)} to ${formatIst(removing.endTime)}. Incidents already assigned keep their assignee.`}
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
