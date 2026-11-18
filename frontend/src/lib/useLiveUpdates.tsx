import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { subscribeToEvents } from '@/api/events'
import { useAuth } from '@/auth/AuthProvider'

type LiveUpdatesContextType = {
  isBackendUnreachable: boolean
}

export const LiveUpdatesContext = createContext<LiveUpdatesContextType>({
  isBackendUnreachable: false,
})

export function useLiveUpdates() {
  return useContext(LiveUpdatesContext)
}

export function LiveUpdatesProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [isBackendUnreachable, setIsBackendUnreachable] = useState(false)

  useEffect(() => {
    if (!user) {
      return
    }

    const unsubscribe = subscribeToEvents({
      onIncidentChanged: ({ id }) => {
        void queryClient.invalidateQueries({ queryKey: ['incidents'], refetchType: 'all' })
        void queryClient.invalidateQueries({ queryKey: ['incident', id] })
      },
      onConnectionChange: (connected) => {
        setIsBackendUnreachable(!connected)
      },
    })

    return unsubscribe
  }, [user, queryClient])

  return (
    <LiveUpdatesContext.Provider value={{ isBackendUnreachable: user ? isBackendUnreachable : false }}>
      {children}
    </LiveUpdatesContext.Provider>
  )
}
