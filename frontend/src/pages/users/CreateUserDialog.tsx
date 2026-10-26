import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { roleSchema, type Role } from '@/api/schemas'
import { createUser } from '@/api/users'
import { ROLE_LABEL } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

// The server checks the same rules; these only save a round trip.
const newUserSchema = z.object({
  username: z.string().trim().min(1, { error: 'Enter a username' }),
  displayName: z.string().trim().min(1, { error: 'Enter the name people will see' }),
  password: z.string().min(8, { error: 'Use at least 8 characters' }).max(72, { error: 'Use at most 72 characters' }),
  role: roleSchema,
})

type NewUserValues = z.infer<typeof newUserSchema>

const EMPTY: NewUserValues = { username: '', displayName: '', password: '', role: 'ENGINEER' }
const ROLES: Role[] = ['ADMIN', 'ENGINEER', 'VIEWER']

export function CreateUserDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const queryClient = useQueryClient()
  const form = useForm<NewUserValues>({ resolver: zodResolver(newUserSchema), defaultValues: EMPTY })

  const close = () => {
    form.reset(EMPTY)
    onOpenChange(false)
  }

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const user = await createUser(values)
      toast.success(`${user.displayName} can now log in as ${user.username}.`)
      await queryClient.invalidateQueries({ queryKey: ['users'] })
      close()
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 409) {
        form.setError('username', { message: error.message })
      } else if (error instanceof ApiRequestError && Object.keys(error.errors).length > 0) {
        for (const [field, message] of Object.entries(error.errors)) {
          if (field in EMPTY) form.setError(field as keyof NewUserValues, { message })
        }
      } else {
        form.setError('root', { message: error instanceof Error ? error.message : 'The user was not saved.' })
      }
    }
  })

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Add a user</DialogTitle>
          <DialogDescription>They log in with this username and password. Only Admins can change their role later.</DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
            <FormField
              control={form.control}
              name="username"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Username</FormLabel>
                  <FormControl>
                    <Input autoComplete="off" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="displayName"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Display name</FormLabel>
                  <FormControl>
                    <Input autoComplete="off" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="password"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Password</FormLabel>
                  <FormControl>
                    <Input type="password" autoComplete="new-password" {...field} />
                  </FormControl>
                  <FormDescription>8 to 72 characters.</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="role"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Role</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {ROLES.map((role) => (
                        <SelectItem key={role} value={role}>
                          {ROLE_LABEL[role]}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />
            {form.formState.errors.root && (
              <p role="alert" className="text-sm font-medium text-destructive">
                {form.formState.errors.root.message}
              </p>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={close}>
                Cancel
              </Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                Add user
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
