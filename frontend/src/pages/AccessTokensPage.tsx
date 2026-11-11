import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { listPersonalAccessTokens, revokePersonalAccessToken, type PersonalAccessToken } from '@/api/personalAccessTokens'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatIst } from '@/lib/time'
import { CreateTokenDialog } from './tokens/CreateTokenDialog'

/** Personal access tokens for the authenticated user to connect AI tools and scripts. */
export function AccessTokensPage() {
  const queryClient = useQueryClient()
  const tokens = useQuery({ queryKey: ['personal-access-tokens'], queryFn: listPersonalAccessTokens })
  const [creating, setCreating] = useState(false)
  // The token stays set while the confirmation dialog closes so its name doesn't vanish mid-animation.
  const [revoking, setRevoking] = useState<PersonalAccessToken | null>(null)
  const [confirming, setConfirming] = useState(false)
  // Read once per visit: a token that passes its 90 days while the page is open still shows Active until a reload.
  const [now] = useState(() => Date.now())

  const revoke = useMutation({
    mutationFn: (token: PersonalAccessToken) => revokePersonalAccessToken(token.id),
    onSuccess: (_, token) => {
      toast.success(`${token.name} has been revoked.`)
      setConfirming(false)
      return queryClient.invalidateQueries({ queryKey: ['personal-access-tokens'] })
    },
    onError: (error) => toast.error(error.message),
  })

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Access tokens
          </h1>
          <p className="text-muted-foreground">
            Personal access tokens let AI tools and scripts authenticate as you. Keep them secret.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <Plus aria-hidden="true" />
          Create token
        </Button>
      </div>

      {tokens.isPending ? (
        <LoadingState message="Loading access tokens" />
      ) : tokens.isError ? (
        <ErrorState message={tokens.error.message} onRetry={() => tokens.refetch()} />
      ) : tokens.data.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          No access tokens yet. Create one to connect AI assistants like Claude or Cursor.
        </p>
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Last used</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="w-[110px]" aria-label="Actions" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {tokens.data.map((token) => {
                // The server refuses a token after 90 days (FR-23.2), so the page says so instead of Active.
                const expired = !token.revokedAt && Date.parse(token.expiresAt) <= now
                return (
                  <TableRow key={token.id}>
                    <TableCell className="font-semibold">{token.name}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(token.createdAt)}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground tnum">
                      {token.lastUsedAt ? formatIst(token.lastUsedAt) : 'Never'}
                    </TableCell>
                    <TableCell>
                      {token.revokedAt ? (
                        <span className="flex items-center gap-2 text-muted-foreground">
                          <span className="size-1.5 rounded-full bg-muted-foreground" aria-hidden="true" />
                          Revoked {formatIst(token.revokedAt)}
                        </span>
                      ) : expired ? (
                        <span className="flex items-center gap-2 text-muted-foreground">
                          <span className="size-1.5 rounded-full bg-muted-foreground" aria-hidden="true" />
                          Expired {formatIst(token.expiresAt)}
                        </span>
                      ) : (
                        <span className="flex items-center gap-2">
                          <span className="size-1.5 rounded-full bg-health-operational" aria-hidden="true" />
                          Active
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      {!token.revokedAt && !expired && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setRevoking(token)
                            setConfirming(true)
                          }}
                          aria-label={`Revoke ${token.name}`}
                        >
                          Revoke
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}

      <CreateTokenDialog open={creating} onOpenChange={setCreating} />

      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Revoke {revoking?.name}?</DialogTitle>
            <DialogDescription>
              Any script or AI assistant using this personal access token will stop working immediately. This action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => revoking && revoke.mutate(revoking)}
              disabled={revoke.isPending}
            >
              Revoke token
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
