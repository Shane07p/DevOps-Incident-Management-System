import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { listTools, revokeTool, type Tool } from '@/api/tools'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatIst } from '@/lib/time'
import { RegisterToolDialog } from './tools/RegisterToolDialog'

/** Monitoring tools and their tokens. The route is Admin-only, and the server checks every request again. */
export function ToolsPage() {
  const queryClient = useQueryClient()
  const tools = useQuery({ queryKey: ['tools'], queryFn: listTools })
  const [registering, setRegistering] = useState(false)
  // The tool stays set while the confirmation closes, so its name doesn't vanish mid-animation.
  const [revoking, setRevoking] = useState<Tool | null>(null)
  const [confirming, setConfirming] = useState(false)

  const revoke = useMutation({
    mutationFn: (tool: Tool) => revokeTool(tool.id),
    onSuccess: (_, tool) => {
      toast.success(`${tool.name}'s token no longer works.`)
      setConfirming(false)
      return queryClient.invalidateQueries({ queryKey: ['tools'] })
    },
    onError: (error) => toast.error(error.message),
  })

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Tools
          </h1>
          <p className="text-muted-foreground">
            Each monitoring tool sends alerts with its own token. Revoke a token to stop that tool.
          </p>
        </div>
        <Button onClick={() => setRegistering(true)}>
          <Plus aria-hidden="true" />
          Register tool
        </Button>
      </div>

      {tools.isPending ? (
        <LoadingState message="Loading tools" />
      ) : tools.isError ? (
        <ErrorState message={tools.error.message} onRetry={() => tools.refetch()} />
      ) : tools.data.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          No tools yet. Register one to give it a token for sending alerts.
        </p>
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Registered</TableHead>
                <TableHead>Token</TableHead>
                <TableHead className="w-[110px]" aria-label="Actions" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {tools.data.map((tool) => (
                <TableRow key={tool.id}>
                  <TableCell className="font-semibold">{tool.name}</TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(tool.createdAt)}</TableCell>
                  <TableCell>
                    {tool.revokedAt ? (
                      <span className="flex items-center gap-2 text-muted-foreground">
                        <span className="size-1.5 rounded-full bg-muted-foreground" aria-hidden="true" />
                        Revoked {formatIst(tool.revokedAt)}
                      </span>
                    ) : (
                      <span className="flex items-center gap-2">
                        <span className="size-1.5 rounded-full bg-health-operational" aria-hidden="true" />
                        Active
                      </span>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    {!tool.revokedAt && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          setRevoking(tool)
                          setConfirming(true)
                        }}
                        aria-label={`Revoke ${tool.name}`}
                      >
                        Revoke
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <RegisterToolDialog open={registering} onOpenChange={setRegistering} />

      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Revoke {revoking?.name}'s token?</DialogTitle>
            <DialogDescription>
              Alerts it sends get 401 from now on. This can't be undone: the tool needs a new registration to send
              alerts again.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={() => revoking && revoke.mutate(revoking)} disabled={revoke.isPending}>
              Revoke token
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
