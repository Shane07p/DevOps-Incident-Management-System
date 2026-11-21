import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { followUpSchema, listFollowUps, updateFollowUp, type FollowUp, type FollowUpsQuery } from '@/api/postmortems'
import { useAuth } from '@/auth/AuthProvider'
import { can } from '@/auth/roles'
import { Avatar } from '@/components/app/Avatar'
import { ErrorState } from '@/components/app/ErrorState'
import { Health } from '@/components/app/Health'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel } from '@/components/app/Panel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

const STATUS_LABEL: Record<FollowUp['status'], string> = { OPEN: 'Open', IN_PROGRESS: 'In progress', DONE: 'Done' }
const dueDateFormat = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })

/** Actions across incidents. The server filters by org and decides what is overdue against its UTC date. */
export function FollowUpsPage() {
  const { user } = useAuth()
  const canEdit = can(user?.role, 'respond')
  const client = useQueryClient()
  const [params, setParams] = useSearchParams()
  const ownerParam = params.get('owner') ?? ''
  const owner = ownerParam === 'me' ? 'me' : /^[1-9]\d*$/.test(ownerParam) && Number.isSafeInteger(Number(ownerParam)) ? Number(ownerParam) : undefined
  const parsedStatus = followUpSchema.shape.status.safeParse(params.get('status'))
  const filters: FollowUpsQuery = {
    owner,
    status: parsedStatus.success ? parsedStatus.data : undefined,
    overdue: params.get('overdue') === 'true' ? true : undefined,
  }
  const hasFilters = filters.owner !== undefined || filters.status !== undefined || filters.overdue === true
  const followUps = useQuery({ queryKey: ['follow-ups', 'org', filters], queryFn: () => listFollowUps(filters) })
  const items = followUps.data?.toSorted((a, b) => a.dueDate.localeCompare(b.dueDate) || a.id - b.id) ?? []
  const setFilter = (field: 'owner' | 'status' | 'overdue', value: string) => {
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (value && value !== '_all') next.set(field, value)
      else next.delete(field)
      return next
    })
  }
  const clearFilters = () => setParams({})
  const changeStatus = useMutation({
    mutationFn: ({ item, status }: { item: FollowUp; status: FollowUp['status'] }) => updateFollowUp(item.id, { status }),
    onSuccess: async (saved) => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ['follow-ups'] }),
        client.invalidateQueries({ queryKey: ['incident', saved.incidentId] }),
      ])
    },
  })

  return (
    <div className="mx-auto flex min-w-0 max-w-[1240px] flex-col">
      <PageHeader title="Follow-ups" description="Actions from every incident" />
      <div className="mb-6 flex flex-wrap items-end gap-4 rounded-lg border bg-card p-[19px] shadow-elev-1">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="owner">Owner</Label>
          <Select value={owner === undefined ? '_all' : String(owner)} onValueChange={(value) => setFilter('owner', value)}>
            <SelectTrigger id="owner" className="w-[145px]"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="_all">Everyone</SelectItem>
              <SelectItem value="me">Mine</SelectItem>
              {typeof owner === 'number' && <SelectItem value={String(owner)}>{items.find((item) => item.owner.id === owner)?.owner.displayName ?? `User ${owner}`}</SelectItem>}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="status">Status</Label>
          <Select value={filters.status ?? '_all'} onValueChange={(value) => setFilter('status', value)}>
            <SelectTrigger id="status" className="w-[145px]"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="_all">All statuses</SelectItem>
              {followUpSchema.shape.status.options.map((status) => <SelectItem key={status} value={status}>{STATUS_LABEL[status]}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex h-9 items-center gap-2">
          <Checkbox id="overdue" checked={filters.overdue === true} onCheckedChange={(checked) => setFilter('overdue', checked === true ? 'true' : '')} />
          <Label htmlFor="overdue">Overdue only</Label>
        </div>
        {hasFilters && items.length > 0 && <Button variant="ghost" onClick={clearFilters}>Clear filters</Button>}
      </div>

      {changeStatus.isError && <p role="alert" className="mb-4 text-sm text-destructive">{changeStatus.error.message}</p>}
      {followUps.isPending ? <LoadingState message="Loading follow-ups" /> : followUps.isError ? (
        <ErrorState title="Follow-ups could not be loaded" message={followUps.error.message} onRetry={() => followUps.refetch()} />
      ) : (
        <Panel title="Follow-ups">
          {items.length === 0 ? (
            <div className="flex flex-col items-center gap-3 px-[19px] pb-8 text-center text-[13px]">
              <p className="text-muted-foreground">{hasFilters ? 'No follow-ups match these filters' : 'No follow-ups yet'}</p>
              {hasFilters && <Button variant="outline" onClick={clearFilters}>Clear filters</Button>}
            </div>
          ) : (
            <Table aria-label="Follow-ups" className="min-w-[900px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-[19px]">Title</TableHead><TableHead>Incident</TableHead><TableHead>Kind</TableHead>
                  <TableHead>Owner</TableHead><TableHead>Due date</TableHead><TableHead className="pr-[19px]">Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell className="min-w-[200px] max-w-[360px] pl-[19px] font-medium break-words whitespace-normal [overflow-wrap:anywhere]">{item.title}</TableCell>
                    <TableCell><Link to={`/incidents/${item.incidentId}`} className="font-mono text-xs text-primary underline">{item.incidentReference}</Link></TableCell>
                    <TableCell>{item.kind === 'CORRECTIVE' ? 'Corrective' : 'Preventive'}</TableCell>
                    <TableCell><span className="flex items-center gap-2.5"><Avatar name={item.owner.displayName} size="sm" /><span className="max-w-[180px] break-words whitespace-normal [overflow-wrap:anywhere]">{item.owner.displayName}</span></span></TableCell>
                    <TableCell><span className="flex flex-wrap items-center gap-2"><time dateTime={item.dueDate} className="font-mono text-xs text-muted-foreground">{dueDateFormat.format(new Date(`${item.dueDate}T00:00:00Z`))}</time>{item.overdue && item.status !== 'DONE' && <Badge variant="outline" className="border-health-down/35 text-health-down-fg">Overdue</Badge>}</span></TableCell>
                    <TableCell className="pr-[19px]">
                      {canEdit ? (
                        <Select value={item.status} disabled={changeStatus.isPending} onValueChange={(status) => { if (status !== item.status) changeStatus.mutate({ item, status: status as FollowUp['status'] }) }}>
                          <SelectTrigger id={`status-${item.id}`} aria-label={`Status for ${item.title}`} className="w-[150px]"><SelectValue /></SelectTrigger>
                          <SelectContent>{followUpSchema.shape.status.options.map((status) => <SelectItem key={status} value={status}>{STATUS_LABEL[status]}</SelectItem>)}</SelectContent>
                        </Select>
                      ) : <Health tone={item.status === 'DONE' ? 'ok' : item.status === 'IN_PROGRESS' ? 'warn' : 'neutral'}>{STATUS_LABEL[item.status]}</Health>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Panel>
      )}
    </div>
  )
}
