import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { Copy, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { createPersonalAccessToken, type CreatePersonalAccessTokenResponse } from '@/api/personalAccessTokens'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const newTokenSchema = z.object({
  name: z.string().trim().min(1, { error: 'Enter a name' }).max(100, { error: 'Use at most 100 characters' }),
})

type NewTokenValues = z.infer<typeof newTokenSchema>

const EMPTY: NewTokenValues = { name: '' }

/** Creates a personal access token, then shows it once. Closing the dialog drops the token from state for good. */
export function CreateTokenDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const queryClient = useQueryClient()
  const form = useForm<NewTokenValues>({ resolver: zodResolver(newTokenSchema), defaultValues: EMPTY })
  // The token lives only here: the server keeps a hash, so it cannot be shown again.
  const [created, setCreated] = useState<CreatePersonalAccessTokenResponse | null>(null)

  const close = () => {
    form.reset(EMPTY)
    setCreated(null)
    onOpenChange(false)
  }

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const response = await createPersonalAccessToken(values.name)
      setCreated(response)
      await queryClient.invalidateQueries({ queryKey: ['personal-access-tokens'] })
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 409) {
        form.setError('name', { message: error.message })
      } else if (error instanceof ApiRequestError && error.errors.name) {
        form.setError('name', { message: error.errors.name })
      } else {
        form.setError('root', { message: error instanceof Error ? error.message : 'The token was not created.' })
      }
    }
  })

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <DialogContent>
        {created ? (
          <TokenStep token={created} onDone={close} />
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Create personal access token</DialogTitle>
              <DialogDescription>
                Personal access tokens allow AI assistants and CLI tools to access Settle on your behalf.
              </DialogDescription>
            </DialogHeader>
            <Form {...form}>
              <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
                <FormField
                  control={form.control}
                  name="name"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel htmlFor="name">Name</FormLabel>
                      <FormControl>
                        <Input id="name" autoComplete="off" placeholder="e.g. Claude Desktop or Cursor" {...field} />
                      </FormControl>
                      <FormDescription>Unique name for your personal access token, up to 100 characters.</FormDescription>
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
                    Create token
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

function TokenStep({ token, onDone }: { token: CreatePersonalAccessTokenResponse; onDone: () => void }) {
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(token.token)
      setCopied(true)
    } catch {
      toast.error('The browser blocked the clipboard. Select the token and copy it by hand.')
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Copy the token for {token.name}</DialogTitle>
        <DialogDescription>
          Use this token to authenticate AI assistants or CLI tools via the Model Context Protocol (MCP) or API.
        </DialogDescription>
      </DialogHeader>
      <p role="alert" className="flex items-start gap-2 rounded-md bg-sev-2-bg p-3 text-sm text-sev-2-fg">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        This is the only time Settle shows it. Store it in a secure password manager or tool config; once you close this, it can't be shown again.
      </p>
      <div className="flex flex-col gap-2">
        <Label htmlFor="token">Token</Label>
        <div className="flex gap-2">
          <Input id="token" readOnly value={token.token} className="font-mono text-xs" onFocus={(event) => event.target.select()} />
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
