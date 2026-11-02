import { z } from 'zod'
import { apiFetch } from './client'

const timestamp = z.iso.datetime({ offset: true })

export const notificationSchema = z.object({
  id: z.number(),
  // Every notification so far is about an incident, but the API allows one without.
  incidentId: z.number().nullable(),
  message: z.string(),
  createdAt: timestamp,
  // Null while unread.
  readAt: timestamp.nullable(),
})

export type Notification = z.infer<typeof notificationSchema>

export const notificationListSchema = z.object({
  notifications: z.array(notificationSchema),
  unreadCount: z.number(),
})

/** Only your own, newest first. */
export function listNotifications() {
  return apiFetch('/notifications', notificationListSchema)
}

/** Another user's notification answers 404, so its existence isn't revealed. */
export function markNotificationRead(id: number) {
  return apiFetch(`/notifications/${id}/read`, notificationSchema, { method: 'POST' })
}
