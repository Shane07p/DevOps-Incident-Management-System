import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { UserCheck, UserPlus, UserX } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import type { Member, Role, User } from '@/api/schemas'
import { changeRole, listUsers, setEnabled } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { ROLE_LABEL } from '@/auth/roles'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Badge } from '@/components/ui/badge'
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

interface AccessChange {
  member: Member
  enabled: boolean
}

/** The org's members and their roles. The route is Admin-only, and the server checks every request again. */
export function UsersPage() {
  const { user: me } = useAuth()
  const queryClient = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers })
  const [creating, setCreating] = useState(false)
  const [pending, setPending] = useState<RoleChange | null>(null)
  const [access, setAccess] = useState<AccessChange | null>(null)

  const roleChange = useMutation({
    mutationFn: ({ user, role }: RoleChange) => changeRole(user.id, role),
    onSuccess: (_, { user, role }) => {
      toast.success(`${user.displayName} is now ${ROLE_LABEL[role]}.`)
      setPending(null)
      return queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: (error) => toast.error(error.message),
  })

  const accessChange = useMutation({
    mutationFn: ({ member, enabled }: AccessChange) => setEnabled(member.id, enabled),
    onSuccess: (_, { member, enabled }) => {
      toast.success(enabled ? `${member.displayName} can sign in again.` : `${member.displayName} is disabled and signed out.`)
      setAccess(null)
      return queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    // A 409 (your own account, or the last Admin) keeps the dialog open with the server's reason in it.
  })
  const openAccess = (change: AccessChange) => {
    accessChange.reset()
    setAccess(change)
  }

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Members
          </h1>
          <p className="text-muted-foreground">Viewers read, Engineers respond to incidents, and Admins configure Settle.</p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <UserPlus aria-hidden="true" />
          Add member
        </Button>
      </div>

      {users.isPending ? (
        <LoadingState message="Loading members" />
      ) : users.isError ? (
        <ErrorState message={users.error.message} onRetry={() => users.refetch()} />
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead className="w-[200px]">Role</TableHead>
                <TableHead className="w-[150px]">
                  <span className="sr-only">Access</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.data.map((user) => (
                <TableRow key={user.id} className={user.enabled ? undefined : 'bg-muted/40'}>
                  <TableCell>
                    <div className="flex flex-col gap-0.5">
                      <span className={'flex items-center gap-1.5 font-semibold' + (user.enabled ? '' : ' text-muted-foreground')}>
                        {user.displayName}
                        {user.id === me?.id && <span className="text-xs font-normal text-muted-foreground">(you)</span>}
                        {!user.enabled && (
                          <Badge variant="outline" className="font-normal text-muted-foreground">
                            Disabled
                          </Badge>
                        )}
                      </span>
                      <span className="font-mono text-xs text-muted-foreground">{user.email}</span>
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
                  <TableCell className="text-right">
                    {/* Your own row has no Disable: the server refuses it too (409). */}
                    {user.id !== me?.id &&
                      (user.enabled ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          aria-label={`Disable ${user.displayName}`}
                          onClick={() => openAccess({ member: user, enabled: false })}
                        >
                          <UserX aria-hidden="true" />
                          Disable
                        </Button>
                      ) : (
                        <Button
                          variant="outline"
                          size="sm"
                          aria-label={`Enable ${user.displayName}`}
                          onClick={() => openAccess({ member: user, enabled: true })}
                        >
                          <UserCheck aria-hidden="true" />
                          Enable
                        </Button>
                      ))}
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

      <Dialog open={access !== null} onOpenChange={(open) => !open && setAccess(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {access && `${access.enabled ? 'Enable' : 'Disable'} ${access.member.displayName}?`}
            </DialogTitle>
            <DialogDescription>
              {access &&
                (access.enabled
                  ? `${access.member.displayName} can sign in again with their password, as ${ROLE_LABEL[access.member.role]}.`
                  : `${access.member.displayName} is signed out everywhere now and can't sign in until an Admin enables them. Their incidents and history stay as they are.`)}
            </DialogDescription>
          </DialogHeader>
          {accessChange.isError && (
            <p role="alert" className="text-sm text-destructive">
              {accessChange.error.message}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setAccess(null)}>
              Cancel
            </Button>
            <Button
              variant={access?.enabled ? 'default' : 'destructive'}
              onClick={() => access && accessChange.mutate(access)}
              disabled={accessChange.isPending}
            >
              {access?.enabled ? 'Enable' : 'Disable'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
