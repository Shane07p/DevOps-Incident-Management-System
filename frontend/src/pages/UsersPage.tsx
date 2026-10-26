import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { UserPlus } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import type { Role, User } from '@/api/schemas'
import { changeRole, listUsers } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { ROLE_LABEL } from '@/auth/roles'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { CreateUserDialog } from './users/CreateUserDialog'

const ROLES: Role[] = ['ADMIN', 'ENGINEER', 'VIEWER']

interface RoleChange {
  user: User
  role: Role
}

/** Accounts and roles. The route is Admin-only, and the server checks every request again. */
export function UsersPage() {
  const { user: me } = useAuth()
  const queryClient = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers })
  const [creating, setCreating] = useState(false)
  const [pending, setPending] = useState<RoleChange | null>(null)

  const roleChange = useMutation({
    mutationFn: ({ user, role }: RoleChange) => changeRole(user.id, role),
    onSuccess: (_, { user, role }) => {
      toast.success(`${user.displayName} is now ${ROLE_LABEL[role]}.`)
      setPending(null)
      return queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: (error) => toast.error(error.message),
  })

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Users
          </h1>
          <p className="text-muted-foreground">Viewers read, Engineers respond to incidents, and Admins configure Settle.</p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <UserPlus aria-hidden="true" />
          Add user
        </Button>
      </div>

      {users.isPending ? (
        <LoadingState message="Loading users" />
      ) : users.isError ? (
        <ErrorState message={users.error.message} onRetry={() => users.refetch()} />
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead className="w-[200px]">Role</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.data.map((user) => (
                <TableRow key={user.id}>
                  <TableCell>
                    <div className="flex flex-col gap-0.5">
                      <span className="font-semibold">
                        {user.displayName}
                        {user.id === me?.id && <span className="ml-1.5 text-xs font-normal text-muted-foreground">(you)</span>}
                      </span>
                      <span className="font-mono text-xs text-muted-foreground">{user.username}</span>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Select
                      value={user.role}
                      onValueChange={(role) => role !== user.role && setPending({ user, role: role as Role })}
                    >
                      <SelectTrigger aria-label={`Role of ${user.displayName}`} className="w-[160px]">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {ROLES.map((role) => (
                          <SelectItem key={role} value={role}>
                            {ROLE_LABEL[role]}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <CreateUserDialog open={creating} onOpenChange={setCreating} />

      <Dialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Change role?</DialogTitle>
            <DialogDescription>
              {pending &&
                (pending.user.id === me?.id
                  ? `You become ${ROLE_LABEL[pending.role]} and are logged out now.`
                  : `${pending.user.displayName} becomes ${ROLE_LABEL[pending.role]}. They are logged out everywhere and log in again with the new role.`)}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPending(null)}>
              Cancel
            </Button>
            <Button onClick={() => pending && roleChange.mutate(pending)} disabled={roleChange.isPending}>
              Change role
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
