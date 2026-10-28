import { z } from 'zod'
import { apiFetch } from './client'

const timestamp = z.iso.datetime({ offset: true })

export const shiftSchema = z.object({
  id: z.number(),
  responderId: z.number(),
  responderUsername: z.string(),
  startTime: timestamp,
  endTime: timestamp,
})

export type Shift = z.infer<typeof shiftSchema>

export const onCallScheduleSchema = z.object({
  serviceId: z.number(),
  // Null until an Admin saves the schedule for the first time.
  fallbackContactId: z.number().nullable(),
  fallbackContactUsername: z.string().nullable(),
  shifts: z.array(shiftSchema),
})

export type OnCallSchedule = z.infer<typeof onCallScheduleSchema>

export interface ShiftRequest {
  responderId: number
  startTime: string
  endTime: string
}

/** The body of PUT /api/services/{id}/on-call. It replaces every shift, so send the whole list. */
export interface OnCallScheduleRequest {
  fallbackContactId: number
  shifts: ShiftRequest[]
}

/** Every role may read a service's schedule. */
export function getOnCall(serviceId: number) {
  return apiFetch(`/services/${serviceId}/on-call`, onCallScheduleSchema)
}

/** Admins only. The server rejects overlapping shifts on the field `shifts`. */
export function updateOnCall(serviceId: number, schedule: OnCallScheduleRequest) {
  return apiFetch(`/services/${serviceId}/on-call`, onCallScheduleSchema, { method: 'PUT', body: schedule })
}
