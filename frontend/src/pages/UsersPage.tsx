import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { UserCheck, UserPlus, UserX } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import type { Member, Role, User } from '@/api/schemas'
import { changeRole, listUsers, setEnabled } from '@/api/users'
import { useAuth } from '@/auth/AuthProvider'
import { ROLE_LABEL } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel, PanelFooter } from '@/components/app/Panel'
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
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Members"
        description="People in this org, how they sign in, and what they can do. Viewers read, Engineers respond, and Admins configure Settle."
        actions={
          <Button onClick={() => setCreating(true)}>
            <UserPlus aria-hidden="true" />
            Add member
          </Button>
        }
      />

      {users.isPending ? (
        <LoadingState message="Loading members" />
      ) : users.isError ? (
        <ErrorState message={users.error.message} onRetry={() => users.refetch()} />
      ) : (
        <Panel>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-[19px]">Member</TableHead>
                  <TableHead>Email</TableHead>
                  <TableHead className="w-[190px]">Role</TableHead>
                  <TableHead className="w-[120px]">Status</TableHead>
                  <TableHead className="w-[130px] pr-[19px]">
                    <span className="sr-only">Access</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.data.map((user) => (
                  <TableRow key={user.id} className={user.enabled ? undefined : 'text-muted-foreground'}>
                    <TableCell className="pl-[19px]">
                      <span className="flex items-center gap-2.5 font-medium">
                        <Avatar name={user.displayName} size="sm" />
                        {user.displayName}
                        {user.id === me?.id && <span className="text-xs font-normal text-muted-foreground">(you)</span>}
                      </span>
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{user.email}</TableCell>
                    <TableCell>
                      <Select
                        value={user.role}
                        onValueChange={(role) => role !== user.role && setPending({ user, role: role as Role })}
                      >
                        <SelectTrigger aria-label={`Role of ${user.displayName}`} className="w-[150px]">
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
                    <TableCell>
                      <Health tone={user.enabled ? 'ok' : 'warn'}>{user.enabled ? 'Active' : 'Disabled'}</Health>
                    </TableCell>
                    <TableCell className="pr-[19px] text-right">
                      {/* Your own row has no Disable: the server refuses it too (409). */}
                      {user.id !== me?.id &&
                        (user.enabled ? (
                          <Button
                            variant="outline"
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
          <PanelFooter>
            <span>Changing a role or disabling someone signs them out everywhere.</span>
            <span className="tnum">{users.data.filter((user) => user.enabled).length} active</span>
          </PanelFooter>
        </Panel>
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
