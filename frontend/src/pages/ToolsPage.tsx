import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { listTools, revokeTool, type Tool } from '@/api/tools'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel, PanelFooter } from '@/components/app/Panel'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatIst } from '@/lib/time'
import { RegisterToolDialog } from './tools/RegisterToolDialog'

// What a tool sends (AlertRequest): every field but correlationKey and details is required.
const CONNECT_EXAMPLE = `POST /webhooks/alerts
Authorization: Bearer <tool token>
Content-Type: application/json

{ "source": "grafana", "eventId": "evt-123", "service": "Payments",
  "title": "High error rate", "description": "5xx above 2% for 5 min",
  "severity": "SEV2", "correlationKey": "payments-5xx" }`

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
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Monitoring tools"
        description="The tools that send alerts to Settle, each with its own token. Revoke a token to stop that tool."
        actions={
          <Button onClick={() => setRegistering(true)}>
            <Plus aria-hidden="true" />
            Register tool
          </Button>
        }
      />

      {tools.isPending ? (
        <LoadingState message="Loading tools" />
      ) : tools.isError ? (
        <ErrorState message={tools.error.message} onRetry={() => tools.refetch()} />
      ) : tools.data.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          No tools yet. Register one to give it a token for sending alerts.
        </p>
      ) : (
        <Panel>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-[19px]">Tool</TableHead>
                  <TableHead>Token</TableHead>
                  <TableHead>Registered</TableHead>
                  <TableHead className="w-[110px] pr-[19px]">
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tools.data.map((tool) => (
                  <TableRow key={tool.id}>
                    <TableCell className="pl-[19px] font-medium">{tool.name}</TableCell>
                    <TableCell>
                      {tool.revokedAt ? (
                        <Health tone="neutral">Revoked {formatIst(tool.revokedAt)}</Health>
                      ) : (
                        <Health tone="ok">Active</Health>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(tool.createdAt)}</TableCell>
                    <TableCell className="pr-[19px] text-right">
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
          <PanelFooter>
            <span>Each tool signs in with its own token. Settle keeps only a hash of it.</span>
            <span>Times in IST</span>
          </PanelFooter>
        </Panel>
      )}

      <Panel title="Connect a tool" className="mt-6">
        <div className="flex flex-col gap-3 px-[19px] pb-[19px]">
          <p className="text-[13px] text-muted-foreground">
            Point the tool&apos;s webhook at Settle&apos;s API and send its token in the header. A repeated{' '}
            <code className="font-mono text-xs">eventId</code> is ignored, and alerts with the same{' '}
            <code className="font-mono text-xs">correlationKey</code> join one open incident.
          </p>
          <pre className="overflow-x-auto rounded-md border bg-muted px-3.5 py-3 font-mono text-xs leading-relaxed">
            {CONNECT_EXAMPLE}
          </pre>
        </div>
      </Panel>

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
