import { useQuery } from '@tanstack/react-query'
import { useCallback, useId } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AUDIT_ITEM_TYPES, listAuditEntries } from '@/api/audit'
import { listUsers } from '@/api/users'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatIst, istInputToUtc } from '@/lib/time'

const ANY = '_any'

const ITEM_LABEL: Record<string, string> = {
  USER: 'Member',
  SERVICE: 'Service',
  INCIDENT: 'Incident',
  TOOL: 'Tool',
  ORG: 'Org',
  MONITOR: 'Monitor',
  RUNBOOK: 'Runbook',
}

// "SERVICE_DEPENDENCIES_UPDATED" → "Service dependencies updated"; new actions still read sensibly.
const actionLabel = (action: string) => {
  const words = action.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** The day after a `YYYY-MM-DD` date, so the chosen end day is included (the API's `to` is exclusive). */
function dayAfter(date: string) {
  const next = new Date(`${date}T00:00:00Z`)
  next.setUTCDate(next.getUTCDate() + 1)
  return next.toISOString().slice(0, 10)
}

type FilterKey = 'itemType' | 'actorId' | 'from' | 'to' | 'page'

/**
 * Admin → Audit log (US-17): every configuration change, newest first, read-only. The filters live in
 * the URL, so a link or a reload shows the same view. Dates are IST days, like every time in the app.
 */
export function AuditLogPage() {
  const [params, setParams] = useSearchParams()
  const itemType = params.get('itemType') ?? ''
  const actorId = params.get('actorId') ?? ''
  const fromDay = params.get('from') ?? ''
  const toDay = params.get('to') ?? ''
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)

  const datesBackwards = Boolean(fromDay && toDay && toDay < fromDay)

  const setFilters = useCallback(
    (changes: Partial<Record<FilterKey, string>>) => {
      setParams((current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(changes)) {
          if (value && value !== ANY) next.set(key, value)
          else next.delete(key)
        }
        // Another filter means another list: start again from its first page.
        if (!('page' in changes)) next.delete('page')
        return next
      })
    },
    [setParams],
  )

  const users = useQuery({ queryKey: ['users'], queryFn: listUsers })
  const query = {
    page: page - 1,
    itemType: itemType || undefined,
    actorId: actorId || undefined,
    from: fromDay ? istInputToUtc(`${fromDay}T00:00`) : undefined,
    to: toDay ? istInputToUtc(`${dayAfter(toDay)}T00:00`) : undefined,
  }
  const entries = useQuery({
    queryKey: ['audit', query],
    queryFn: () => listAuditEntries(query),
    enabled: !datesBackwards,
    placeholderData: (previous) => previous,
  })

  const ids = { itemType: useId(), actor: useId(), from: useId(), to: useId() }
  const hasFilters = Boolean(itemType || actorId || fromDay || toDay)
  const totalPages = entries.data ? Math.max(1, Math.ceil(entries.data.totalElements / entries.data.size)) : 1

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[44px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
          Audit log
        </h1>
        <p className="text-muted-foreground">
          Every configuration change in your org, newest first. Entries can't be edited or deleted. Times are IST.
        </p>
      </div>

      <div className="grid gap-4 rounded-lg border bg-card p-4 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_1fr_1fr_auto] lg:items-end">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.itemType}>Item</Label>
          <Select value={itemType || ANY} onValueChange={(value) => setFilters({ itemType: value })}>
            <SelectTrigger id={ids.itemType} className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ANY}>Any item</SelectItem>
              {AUDIT_ITEM_TYPES.map((type) => (
                <SelectItem key={type} value={type}>
                  {ITEM_LABEL[type]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.actor}>Changed by</Label>
          <Select value={actorId || ANY} onValueChange={(value) => setFilters({ actorId: value })} disabled={!users.data}>
            <SelectTrigger id={ids.actor} className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ANY}>Anyone</SelectItem>
              {users.data?.map((user) => (
                <SelectItem key={user.id} value={String(user.id)}>
                  {user.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.from}>From (IST)</Label>
          <Input id={ids.from} type="date" value={fromDay} onChange={(event) => setFilters({ from: event.target.value })} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.to}>To (IST)</Label>
          <Input
            id={ids.to}
            type="date"
            value={toDay}
            aria-invalid={datesBackwards}
            aria-describedby={datesBackwards ? `${ids.to}-message` : undefined}
            onChange={(event) => setFilters({ to: event.target.value })}
          />
        </div>
        <Button variant="outline" disabled={!hasFilters} onClick={() => setParams({})}>
          Clear filters
        </Button>
        {datesBackwards && (
          <p id={`${ids.to}-message`} role="alert" className="text-sm text-destructive sm:col-span-2 lg:col-span-5">
            The end date is before the start date.
          </p>
        )}
      </div>

      {datesBackwards ? null : entries.isPending ? (
        <LoadingState message="Loading the audit log" />
      ) : entries.isError ? (
        <ErrorState message={entries.error.message} onRetry={() => entries.refetch()} />
      ) : entries.data.content.length === 0 ? (
        <p className="rounded-lg border bg-card p-8 text-center text-muted-foreground">
          {hasFilters ? 'No changes match these filters.' : 'No changes yet.'}
        </p>
      ) : (
        <>
          <div className="rounded-lg border bg-card">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[150px]">When</TableHead>
                  <TableHead>Changed by</TableHead>
                  <TableHead>Change</TableHead>
                  <TableHead>Item</TableHead>
                  <TableHead>Details</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.data.content.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell className="font-mono text-xs text-muted-foreground tnum">{formatIst(entry.recordedAt)}</TableCell>
                    <TableCell className="font-semibold">{entry.actorDisplayName ?? 'System'}</TableCell>
                    <TableCell>{actionLabel(entry.action)}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {ITEM_LABEL[entry.itemType] ?? actionLabel(entry.itemType)} #{entry.itemId}
                    </TableCell>
                    <TableCell className="max-w-[420px] text-sm break-words whitespace-normal">{entry.details}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="flex items-center justify-between gap-4 text-sm text-muted-foreground">
            <span>
              Page {page} of {totalPages} ({entries.data.totalElements} changes)
            </span>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setFilters({ page: String(page - 1) })}>
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={page >= totalPages}
                onClick={() => setFilters({ page: String(page + 1) })}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
