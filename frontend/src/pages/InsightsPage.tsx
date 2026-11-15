import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchInsights, type InsightsDays, type InsightsResponse } from '@/api/insights'
import type { Severity } from '@/api/schemas'
import { SeverityBadge } from '@/components/app/badges'
import { ErrorState } from '@/components/app/ErrorState'
import { LoadingState } from '@/components/app/LoadingState'
import { PageHeader } from '@/components/app/PageHeader'
import { Panel } from '@/components/app/Panel'
import { cn } from '@/lib/utils'
import { formatDuration, formatPercentage } from './insights/format'

const VALID_DAYS = [7, 30, 90] as const
const DEFAULT_DAYS: InsightsDays = 30

const ALL_SEVERITIES: Severity[] = ['SEV1', 'SEV2', 'SEV3', 'SEV4']

const SEVERITY_BAR_BG: Record<Severity, string> = {
  SEV1: 'bg-sev-1',
  SEV2: 'bg-sev-2',
  SEV3: 'bg-sev-3',
  SEV4: 'bg-sev-4',
}

function RangeSelector({
  days,
  onChange,
}: {
  days: InsightsDays
  onChange: (days: InsightsDays) => void
}) {
  return (
    <div role="group" aria-label="Time range" className="inline-flex rounded-lg border bg-muted p-0.5">
      {VALID_DAYS.map((d) => (
        <button
          key={d}
          type="button"
          aria-pressed={days === d}
          onClick={() => onChange(d)}
          className={cn(
            'rounded-md px-3 py-1 text-[13px] font-medium transition-colors',
            days === d ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {d} days
        </button>
      ))}
    </div>
  )
}

function MetricCards({ data }: { data: InsightsResponse }) {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {/* MTTA */}
      <Panel title="Mean Time to Acknowledge (MTTA)">
        <div className="flex flex-col gap-4 p-5">
          <div>
            <span className="text-[12px] font-medium text-muted-foreground">Mean</span>
            <div className="font-mono text-2xl font-semibold tnum">{formatDuration(data.mtta.mean)}</div>
          </div>
          <div className="grid grid-cols-2 gap-3 border-t pt-3">
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">Median</span>
              <div className="font-mono text-[15px] font-medium tnum">{formatDuration(data.mtta.median)}</div>
            </div>
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">P90</span>
              <div className="font-mono text-[15px] font-medium tnum">{formatDuration(data.mtta.p90)}</div>
            </div>
          </div>
        </div>
      </Panel>

      {/* MTTR */}
      <Panel title="Mean Time to Resolve (MTTR)">
        <div className="flex flex-col gap-4 p-5">
          <div>
            <span className="text-[12px] font-medium text-muted-foreground">Mean</span>
            <div className="font-mono text-2xl font-semibold tnum">{formatDuration(data.mttr.mean)}</div>
          </div>
          <div className="grid grid-cols-2 gap-3 border-t pt-3">
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">Median</span>
              <div className="font-mono text-[15px] font-medium tnum">{formatDuration(data.mttr.median)}</div>
            </div>
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">P90</span>
              <div className="font-mono text-[15px] font-medium tnum">{formatDuration(data.mttr.p90)}</div>
            </div>
          </div>
        </div>
      </Panel>

      {/* SLA Breach Rate */}
      <Panel title="SLA breach rate">
        <div className="flex flex-col gap-4 p-5">
          <div>
            <span className="text-[12px] font-medium text-muted-foreground">Response breach</span>
            <div className="font-mono text-2xl font-semibold tnum">{formatPercentage(data.slaBreachRate.response)}</div>
          </div>
          <div className="border-t pt-3">
            <span className="text-[12px] font-medium text-muted-foreground">Resolution breach</span>
            <div className="font-mono text-[15px] font-medium tnum">{formatPercentage(data.slaBreachRate.resolution)}</div>
          </div>
        </div>
      </Panel>

      {/* Follow-up Completion */}
      <Panel title="Follow-up completion">
        <div className="flex flex-col gap-4 p-5">
          <div>
            <span className="text-[12px] font-medium text-muted-foreground">Completion rate</span>
            <div className="font-mono text-2xl font-semibold tnum">{formatPercentage(data.followUps.completionRate)}</div>
          </div>
          <div className="grid grid-cols-3 gap-2 border-t pt-3">
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">Total</span>
              <div className="font-mono text-[15px] font-medium tnum">{data.followUps.total}</div>
            </div>
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">Done</span>
              <div className="font-mono text-[15px] font-medium tnum">{data.followUps.done}</div>
            </div>
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">Overdue</span>
              <div className="font-mono text-[15px] font-medium tnum">{data.followUps.overdue}</div>
            </div>
          </div>
        </div>
      </Panel>

      {/* Out-of-hours pages */}
      <Panel title="Out-of-hours pages">
        <div className="flex flex-col gap-2 p-5">
          <span className="text-[12px] font-medium text-muted-foreground">Total pages</span>
          <div className="font-mono text-2xl font-semibold tnum">{data.outOfHoursPages}</div>
          <p className="mt-2 text-[12px] text-muted-foreground">
            Pages triggered outside business hours (09:00 - 18:00 IST Monday to Friday).
          </p>
        </div>
      </Panel>

      {/* Incident volume */}
      <Panel title="Incident volume">
        <div className="flex flex-col gap-4 p-5">
          <div>
            <span className="text-[12px] font-medium text-muted-foreground">Total incidents</span>
            <div className="font-mono text-2xl font-semibold tnum">{data.incidents.total}</div>
          </div>
          <div className="grid grid-cols-2 gap-3 border-t pt-3">
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">Open</span>
              <div className="font-mono text-[15px] font-medium tnum">{data.incidents.open}</div>
            </div>
            <div>
              <span className="text-[12px] font-medium text-muted-foreground">Resolved</span>
              <div className="font-mono text-[15px] font-medium tnum">{data.incidents.resolved}</div>
            </div>
          </div>
        </div>
      </Panel>
    </div>
  )
}

function SeverityBreakdownPanel({ data }: { data: InsightsResponse }) {
  const total = data.incidents.total
  const hasData = total > 0 && data.bySeverity.length > 0

  // Index breakdown items by severity
  const severityMap = new Map(data.bySeverity.map((item) => [item.severity, item.count]))

  return (
    <Panel title="Incidents by severity">
      {!hasData ? (
        <p className="border-t px-[19px] py-6 text-[13px] text-muted-foreground">No data yet</p>
      ) : (
        <div className="flex flex-col gap-4 border-t px-[19px] py-4">
          {ALL_SEVERITIES.map((sev) => {
            const count = severityMap.get(sev) ?? 0
            const percentage = total > 0 ? (count / total) * 100 : 0
            return (
              <div key={sev} className="flex flex-col gap-1.5">
                <div className="flex items-center justify-between text-[13px]">
                  <div className="flex items-center gap-2">
                    <SeverityBadge severity={sev} />
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="font-mono font-medium tnum">{count}</span>
                    <span className="w-12 text-right font-mono text-[12px] text-muted-foreground tnum">
                      {percentage.toFixed(1)}%
                    </span>
                  </div>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className={cn('h-full rounded-full transition-all', SEVERITY_BAR_BG[sev])}
                    style={{ width: `${percentage}%` }}
                  />
                </div>
              </div>
            )
          })}
        </div>
      )}
    </Panel>
  )
}

function ServiceBreakdownPanel({ data }: { data: InsightsResponse }) {
  const services = data.byService
  const hasData = services.length > 0
  const maxCount = hasData ? Math.max(...services.map((s) => s.count), 1) : 1

  return (
    <Panel title="Incidents by service">
      {!hasData ? (
        <p className="border-t px-[19px] py-6 text-[13px] text-muted-foreground">No data yet</p>
      ) : (
        <div className="flex flex-col gap-4 border-t px-[19px] py-4">
          {services.map((item) => {
            const barWidth = maxCount > 0 ? (item.count / maxCount) * 100 : 0
            return (
              <Link
                key={item.serviceId}
                to={`/services/${item.serviceId}`}
                className="group -m-1 flex flex-col gap-1.5 rounded-md p-1 transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              >
                <div className="flex items-center justify-between text-[13px]">
                  <span className="truncate font-medium group-hover:underline">{item.name}</span>
                  <span className="font-mono font-medium tnum text-foreground">{item.count}</span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full rounded-full bg-primary transition-all"
                    style={{ width: `${barWidth}%` }}
                  />
                </div>
              </Link>
            )
          })}
        </div>
      )}
    </Panel>
  )
}

/**
 * Insights page (/insights): Incident analytics, MTTA, MTTR, SLA performance,
 * severity distributions, and service breakdown over 7, 30, or 90 days.
 */
export function InsightsPage() {
  const [params, setParams] = useSearchParams()

  const rawDays = Number(params.get('days'))
  const days: InsightsDays = VALID_DAYS.includes(rawDays as InsightsDays) ? (rawDays as InsightsDays) : DEFAULT_DAYS

  const setRange = (newDays: InsightsDays) => {
    const next = new URLSearchParams(params)
    next.set('days', String(newDays))
    setParams(next)
  }

  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['insights', days],
    queryFn: () => fetchInsights(days),
  })

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col">
      <PageHeader
        title="Insights"
        description="Incident volume, MTTA, MTTR, SLA performance, and paging trends across your services."
        actions={<RangeSelector days={days} onChange={setRange} />}
      />

      {isPending ? (
        <LoadingState message="Loading insights…" />
      ) : isError ? (
        <ErrorState
          title="Failed to load insights"
          message={error instanceof Error ? error.message : 'An error occurred while fetching insights.'}
          onRetry={() => refetch()}
        />
      ) : data ? (
        <div className="flex flex-col gap-6">
          <MetricCards data={data} />
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <SeverityBreakdownPanel data={data} />
            <ServiceBreakdownPanel data={data} />
          </div>
        </div>
      ) : null}
    </div>
  )
}
