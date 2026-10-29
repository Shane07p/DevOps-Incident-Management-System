import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { Copy, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { registerTool, type RegisteredTool } from '@/api/tools'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

// The server checks the same rules; these only save a round trip.
const newToolSchema = z.object({
  name: z.string().trim().min(1, { error: 'Enter a name' }).max(100, { error: 'Use at most 100 characters' }),
})

type NewToolValues = z.infer<typeof newToolSchema>

const EMPTY: NewToolValues = { name: '' }

/** Registers a tool, then shows its token once. Closing the dialog drops the token for good. */
export function RegisterToolDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const queryClient = useQueryClient()
  const form = useForm<NewToolValues>({ resolver: zodResolver(newToolSchema), defaultValues: EMPTY })
  // The token lives only here: the server keeps a hash, so it can't be shown again.
  const [registered, setRegistered] = useState<RegisteredTool | null>(null)

  const close = () => {
    form.reset(EMPTY)
    setRegistered(null)
    onOpenChange(false)
  }

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      setRegistered(await registerTool(values.name))
      await queryClient.invalidateQueries({ queryKey: ['tools'] })
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 409) {
        form.setError('name', { message: error.message })
      } else if (error instanceof ApiRequestError && error.errors.name) {
        form.setError('name', { message: error.errors.name })
      } else {
        form.setError('root', { message: error instanceof Error ? error.message : 'The tool was not registered.' })
      }
    }
  })

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <DialogContent>
        {registered ? (
          <TokenStep tool={registered} onDone={close} />
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Register a tool</DialogTitle>
              <DialogDescription>It gets its own token for sending alerts. You can revoke it later.</DialogDescription>
            </DialogHeader>
            <Form {...form}>
              <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
                <FormField
                  control={form.control}
                  name="name"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Name</FormLabel>
                      <FormControl>
                        <Input autoComplete="off" placeholder="e.g. Grafana production" {...field} />
                      </FormControl>
                      <FormDescription>Unique, regardless of case.</FormDescription>
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
                    Register tool
                  </Button>
                </DialogFooter>
              </form>
            </Form>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

function TokenStep({ tool, onDone }: { tool: RegisteredTool; onDone: () => void }) {
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(tool.token)
      setCopied(true)
    } catch {
      toast.error('The browser blocked the clipboard. Select the token and copy it by hand.')
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Copy the token for {tool.name}</DialogTitle>
        <DialogDescription>
          The tool sends it in the <code className="font-mono text-xs">Authorization: Bearer</code> header with each alert
          to <code className="font-mono text-xs">/webhooks/alerts</code>.
        </DialogDescription>
      </DialogHeader>
      <p role="alert" className="flex items-start gap-2 rounded-md bg-sev-2-bg p-3 text-sm text-sev-2-fg">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        This is the only time Settle shows it. Store it in the tool now; once you close this, it can't be shown again.
      </p>
      <div className="flex flex-col gap-2">
        <Label htmlFor="token">Token</Label>
        <div className="flex gap-2">
          <Input id="token" readOnly value={tool.token} className="font-mono text-xs" onFocus={(event) => event.target.select()} />
          <Button type="button" variant="outline" onClick={copy}>
            <Copy aria-hidden="true" />
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
      </div>
      <DialogFooter>
        <Button type="button" onClick={onDone}>
          Done
        </Button>
      </DialogFooter>
    </>
  )
}
