import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { listNotifications, markNotificationRead, type Notification } from '@/api/notifications'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import { formatIst } from '@/lib/time'

/** The bell in the top bar: your unread count, and your notifications. Opening one marks it read. */
export function NotificationBell() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  // Notifications arrive with no push yet, so the count refreshes every 30 seconds.
  const notifications = useQuery({ queryKey: ['notifications'], queryFn: listNotifications, refetchInterval: 30_000 })
  const markRead = useMutation({
    mutationFn: markNotificationRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })

  const unread = notifications.data?.unreadCount ?? 0

  const openNotification = (notification: Notification) => {
    setOpen(false)
    if (notification.readAt === null) markRead.mutate(notification.id)
    if (notification.incidentId !== null) navigate(`/incidents/${notification.incidentId}`)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        aria-label={unread === 0 ? 'Notifications' : `Notifications, ${unread} unread`}
        className="relative grid size-[34px] place-items-center rounded-full border bg-card transition-colors outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring"
      >
        <Bell className="size-[15px]" aria-hidden="true" />
        {unread > 0 && (
          <span
            aria-hidden="true"
            className="absolute -top-1 -right-1 grid h-[18px] min-w-[18px] place-items-center rounded-full bg-primary px-1 font-mono text-[10.5px] font-bold text-primary-foreground tnum"
          >
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 p-0">
        <div className="flex items-baseline justify-between border-b px-4 py-3">
          <h2 className="text-sm font-semibold">Notifications</h2>
          <span className="text-xs text-muted-foreground">{unread} unread</span>
        </div>
        {notifications.isPending ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">Loading…</p>
        ) : notifications.isError ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">Notifications didn't load. Try again shortly.</p>
        ) : notifications.data.notifications.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">No notifications yet.</p>
        ) : (
          <ul className="max-h-[360px] divide-y overflow-y-auto">
            {notifications.data.notifications.map((notification) => {
              const isUnread = notification.readAt === null
              return (
                <li key={notification.id}>
                  <button
                    type="button"
                    onClick={() => openNotification(notification)}
                    className="flex w-full gap-3 px-4 py-3 text-left transition-colors hover:bg-muted"
                  >
                    <span
                      aria-hidden="true"
                      className={cn('mt-1.5 size-2 shrink-0 rounded-full', isUnread ? 'bg-primary' : 'bg-transparent')}
                    />
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <span className={cn('text-[13px]', isUnread && 'font-semibold')}>
                        {isUnread && <span className="sr-only">Unread: </span>}
                        {notification.message}
                      </span>
                      <span className="font-mono text-[11px] text-muted-foreground">{formatIst(notification.createdAt)}</span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </PopoverContent>
    </Popover>
  )
}
