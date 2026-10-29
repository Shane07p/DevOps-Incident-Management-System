import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useFieldArray, useForm } from 'react-hook-form'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import type { EscalationLevel, EscalationLevelRequest } from '@/api/escalation'
import type { User } from '@/api/schemas'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { showRequestErrors } from '@/lib/forms'

const isWholeMinutes = (value: string) => /^\d+$/.test(value) && Number(value) >= 1

// The server checks every rule again; these only save a round trip.
const levelsFormSchema = z.object({
  levels: z.array(
    z.object({
      responderId: z.string().min(1, { error: 'Choose a contact' }),
      waitMinutes: z
        .string()
        .trim()
        .min(1, { error: 'Enter minutes' })
        .refine(isWholeMinutes, { error: 'Use whole minutes, at least 1' }),
    }),
  ),
})

type LevelsFormValues = z.infer<typeof levelsFormSchema>

const NEW_LEVEL = { responderId: '', waitMinutes: '15' }

/** Admins add, remove, and reorder levels, then save them together; `onSave` throws the server's error if it refuses. */
export function EscalationLevelsForm({
  levels,
  users,
  onSave,
}: {
  levels: EscalationLevel[]
  users: User[]
  onSave: (steps: EscalationLevelRequest[]) => Promise<unknown>
}) {
  const form = useForm<LevelsFormValues>({
    resolver: zodResolver(levelsFormSchema),
    // A policy can't be empty, so a service without one starts with one level to fill in.
    defaultValues: {
      levels:
        levels.length > 0
          ? levels.map((level) => ({ responderId: String(level.responderId), waitMinutes: String(level.waitMinutes) }))
          : [NEW_LEVEL],
    },
  })
  const { fields, append, remove, move } = useFieldArray({ control: form.control, name: 'levels' })

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      await onSave(
        values.levels.map((level, index) => ({
          stepNumber: index + 1,
          responderId: Number(level.responderId),
          waitMinutes: Number(level.waitMinutes),
        })),
      )
    } catch (error) {
      // The server names `steps`, which has no input of its own, so its message goes above the buttons.
      if (error instanceof ApiRequestError) showRequestErrors(form, error, [])
      else form.setError('root', { message: 'The levels were not saved. Try again.' })
    }
  })

  return (
    <Form {...form}>
      <form onSubmit={onSubmit} noValidate aria-label="Escalation levels" className="flex flex-col gap-4 rounded-lg border bg-card p-4">
        <div className="flex flex-col gap-1">
          <h3 className="font-semibold">Change the levels</h3>
          <p className="text-sm text-muted-foreground">
            Each level is paged its wait after the level before it; the first, after the incident opens.
          </p>
        </div>
        <ol className="flex flex-col gap-3">
          {fields.map((level, index) => (
            <li key={level.id}>
              <fieldset className="grid gap-3 rounded-md border p-3 sm:grid-cols-[2.5rem_1fr_9rem_auto] sm:items-start">
                <legend className="sr-only">Level {index + 1}</legend>
                <span aria-hidden="true" className="font-mono text-sm font-semibold sm:pt-8">
                  L{index + 1}
                </span>
                <FormField
                  control={form.control}
                  name={`levels.${index}.responderId`}
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Contact</FormLabel>
                      <Select value={field.value} onValueChange={field.onChange}>
                        <FormControl>
                          <SelectTrigger className="w-full">
                            <SelectValue placeholder="Choose a contact" />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent>
                          {users.map((user) => (
                            <SelectItem key={user.id} value={String(user.id)}>
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
                  name={`levels.${index}.waitMinutes`}
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Wait (minutes)</FormLabel>
                      <FormControl>
                        <Input inputMode="numeric" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <div className="flex gap-1 sm:pt-6">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Move level ${index + 1} up`}
                    disabled={index === 0}
                    onClick={() => move(index, index - 1)}
                  >
                    <ArrowUp aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Move level ${index + 1} down`}
                    disabled={index === fields.length - 1}
                    onClick={() => move(index, index + 1)}
                  >
                    <ArrowDown aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Remove level ${index + 1}`}
                    // A policy needs at least one level (the server refuses an empty one).
                    disabled={fields.length === 1}
                    onClick={() => remove(index)}
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
                </div>
              </fieldset>
            </li>
          ))}
        </ol>
        {form.formState.errors.root && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {form.formState.errors.root.message}
          </p>
        )}
        <div className="flex flex-wrap justify-between gap-2">
          <Button type="button" variant="outline" onClick={() => append(NEW_LEVEL)}>
            <Plus aria-hidden="true" />
            Add level
          </Button>
          <Button type="submit" disabled={form.formState.isSubmitting}>
            Save levels
          </Button>
        </div>
      </form>
    </Form>
  )
}
