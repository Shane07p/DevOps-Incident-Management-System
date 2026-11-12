
import { formatIst } from '@/lib/time'

const now = Date.now()

const heartbeats = [
  {
    id: 1,
    name: 'Daily database backup',
    description: 'Production database backup job',
    intervalMinutes: 60,
    lastPingAt: new Date(now - 3 * 60 * 1000).toISOString(),
    nextDueAt: new Date(now + 57 * 60 * 1000).toISOString(),
    status: 'HEALTHY',
  },
  {
    id: 2,
    name: 'Payment reconciliation',
    description: 'Payment reconciliation scheduled job',
    intervalMinutes: 15,
    lastPingAt: new Date(now - 18 * 60 * 1000).toISOString(),
    nextDueAt: new Date(now - 3 * 60 * 1000).toISOString(),
    status: 'MISSED',
  },
  {
    id: 3,
    name: 'Email queue worker',
    description: 'Background email processing',
    intervalMinutes: 10,
    lastPingAt: new Date(now - 8 * 60 * 1000).toISOString(),
    nextDueAt: new Date(now + 2 * 60 * 1000).toISOString(),
    status: 'HEALTHY',
  },
]

export function HeartbeatsPage() {
  const healthyCount = heartbeats.filter(
    (item) => item.status === 'HEALTHY',
  ).length

  const missedCount = heartbeats.filter(
    (item) => item.status === 'MISSED',
  ).length

  return (
    <div className="mx-auto flex max-w-[1180px] flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-2">
          <h1 className="text-[44px] leading-[0.9] font-[750]">
            Heartbeats
          </h1>
          <p className="text-muted-foreground">
            Track scheduled jobs and make sure they check in on time.
          </p>
        </div>

        <span className="rounded-full border px-3 py-1 text-xs text-muted-foreground">
          Sample data
        </span>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <div className="rounded-lg border bg-card p-4">
          <p className="text-sm text-muted-foreground">Total jobs</p>
          <p className="mt-2 text-3xl font-semibold">
            {heartbeats.length}
          </p>
        </div>

        <div className="rounded-lg border bg-card p-4">
          <p className="text-sm text-muted-foreground">Healthy</p>
          <p className="mt-2 text-3xl font-semibold text-green-600">
            {healthyCount}
          </p>
        </div>

        <div className="rounded-lg border bg-card p-4">
          <p className="text-sm text-muted-foreground">Missed</p>
          <p className="mt-2 text-3xl font-semibold text-red-600">
            {missedCount}
          </p>
        </div>
      </div>

      <div className="overflow-x-auto rounded-lg border bg-card">
        <table className="w-full text-sm">
          <thead className="border-b bg-muted/40 text-left">
            <tr>
              <th className="p-4">Heartbeat</th>
              <th className="p-4">Status</th>
              <th className="p-4">Interval</th>
              <th className="p-4">Last ping</th>
              <th className="p-4">Next due time</th>
            </tr>
          </thead>

          <tbody>
            {heartbeats.map((heartbeat) => (
              <tr key={heartbeat.id} className="border-b last:border-0">
                <td className="p-4">
                  <div className="font-semibold">
                    {heartbeat.name}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    {heartbeat.description}
                  </div>
                </td>

                <td className="p-4">
                  <span
                    className={
                      heartbeat.status === 'HEALTHY'
                        ? 'font-medium text-green-600'
                        : 'font-medium text-red-600'
                    }
                  >
                    ● {heartbeat.status === 'HEALTHY' ? 'Healthy' : 'Missed'}
                  </span>
                </td>

                <td className="p-4">
                  {heartbeat.intervalMinutes} minutes
                </td>

                <td className="p-4 font-mono text-xs">
                  {formatIst(heartbeat.lastPingAt)}
                </td>

                <td className="p-4 font-mono text-xs">
                  {formatIst(heartbeat.nextDueAt)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="text-xs text-muted-foreground">
        These are demonstration records. Live heartbeat registration and
        ping data require a backend heartbeat API.
      </p>
    </div>
  )
}
