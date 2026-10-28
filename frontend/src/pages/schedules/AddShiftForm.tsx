import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import type { OnCallSchedule, ShiftRequest } from '@/api/onCall'
import type { User } from '@/api/schemas'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { formatIst, istInputToUtc } from '@/lib/time'
import { findOverlap } from './schedule'

// Times are typed in IST (D12). The server checks every rule again, overlaps included.
const shiftFormSchema = z
  .object({
    responderId: z.string().min(1, { error: 'Choose a responder' }),
    startTime: z.string().min(1, { error: 'Enter a start time' }),
    endTime: z.string().min(1, { error: 'Enter an end time' }),
  })
  .refine(({ startTime, endTime }) => !startTime || !endTime || endTime > startTime, {
    error: 'End the shift after it starts',
    path: ['endTime'],
  })

type ShiftFormValues = z.infer<typeof shiftFormSchema>

const EMPTY: ShiftFormValues = { responderId: '', startTime: '', endTime: '' }

/** Admins add one shift; `onAdd` saves the whole schedule and throws the server's error if it refuses. */
export function AddShiftForm({
  schedule,
  users,
  onAdd,
}: {
  schedule: OnCallSchedule
  users: User[]
  onAdd: (shift: ShiftRequest) => Promise<unknown>
}) {
  const form = useForm<ShiftFormValues>({ resolver: zodResolver(shiftFormSchema), defaultValues: EMPTY })

  const onSubmit = form.handleSubmit(async (values) => {
    const shift = {
      responderId: Number(values.responderId),
      startTime: istInputToUtc(values.startTime),
      endTime: istInputToUtc(values.endTime),
    }
    const clash = findOverlap(schedule.shifts, Date.parse(shift.startTime), Date.parse(shift.endTime))
    if (clash) {
      form.setError('root', {
        message: `This overlaps ${clash.responderUsername}'s shift, ${formatIst(clash.startTime)} to ${formatIst(clash.endTime)}.`,
      })
      return
    }
    try {
      await onAdd(shift)
      form.reset(EMPTY)
    } catch (error) {
      // The server names `shifts` or `fallbackContactId`; neither is an input here, so show it on the form.
      const messages = error instanceof ApiRequestError ? Object.values(error.errors) : []
      const fallback = error instanceof Error ? error.message : 'The shift was not saved. Try again.'
      form.setError('root', { message: messages.length > 0 ? messages.join(' ') : fallback })
    }
  })

  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate aria-label="Add a shift" className="flex flex-col gap-4 rounded-lg border bg-card p-4">
        <h3 className="font-semibold">Add a shift</h3>
        <div className="grid gap-4 sm:grid-cols-3">
          <FormField
            control={form.control}
            name="responderId"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Responder</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger className="w-full">
                      <SelectValue placeholder="Choose a responder" />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {users.map((user) => (
                      <SelectItem key={user.id} value={String(user.id)}>
                        {/* The schedule shows usernames, so name both. */}
                        {user.displayName} ({user.username})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="startTime"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Starts (IST)</FormLabel>
                <FormControl>
                  <Input type="datetime-local" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="endTime"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Ends (IST)</FormLabel>
                <FormControl>
                  <Input type="datetime-local" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </div>
        {form.formState.errors.root && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {form.formState.errors.root.message}
          </p>
        )}
        <div className="flex justify-end">
          <Button type="submit" disabled={form.formState.isSubmitting}>
            Add shift
          </Button>
        </div>
      </form>
    </Form>
  )
}
