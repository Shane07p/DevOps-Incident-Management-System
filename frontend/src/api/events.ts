import { readToken } from './token'

export type IncidentChangedEvent = {
  id: number
}

export type EventSubscriptionOptions = {
  onIncidentChanged: (event: IncidentChangedEvent) => void
  onConnectionChange?: (connected: boolean) => void
}

/**
 * Streams server-sent events from /api/events using fetch (FR-12.3, NFR-14, D46).
 * Keeps the JWT token in the Authorization header rather than the URL.
 * Automatically reconnects with exponential backoff on stream disconnect.
 */
export function subscribeToEvents(options: EventSubscriptionOptions): () => void {
  let active = true
  let controller = new AbortController()
  let retryDelay = 1000
  const maxDelay = 30000
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  async function connect() {
    if (!active) return

    const token = readToken()
    if (!token) {
      options.onConnectionChange?.(false)
      if (active) {
        retryTimer = setTimeout(connect, retryDelay)
        retryDelay = Math.min(retryDelay * 2, maxDelay)
      }
      return
    }

    try {
      controller = new AbortController()
      const response = await fetch('/api/events', {
        headers: {
          Accept: 'text/event-stream',
          Authorization: `Bearer ${token}`,
        },
        signal: controller.signal,
      })

      if (!response.ok || !response.body) {
        throw new Error(`Failed to connect to /api/events: ${response.status}`)
      }

      options.onConnectionChange?.(true)

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (active) {
        const { value, done } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const parts = buffer.split('\n\n')
        buffer = parts.pop() ?? ''

        for (const block of parts) {
          if (parseAndDispatchBlock(block, options.onIncidentChanged)) {
            retryDelay = 1000
          }
        }
      }
    } catch {
      // Fetch aborted or network failure
    }

    if (active) {
      options.onConnectionChange?.(false)
      retryTimer = setTimeout(connect, retryDelay)
      retryDelay = Math.min(retryDelay * 2, maxDelay)
    }
  }

  function parseAndDispatchBlock(block: string, onIncidentChanged: (event: IncidentChangedEvent) => void): boolean {
    const lines = block.split('\n')
    let eventName = 'message'
    let data = ''

    for (const rawLine of lines) {
      const line = rawLine.trim()
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        data = line.slice(5).trim()
      }
    }

    if (eventName === 'incident-changed' && data) {
      try {
        const parsed = JSON.parse(data) as { id?: number }
        if (typeof parsed?.id === 'number') {
          onIncidentChanged({ id: parsed.id })
          return true
        }
      } catch {
        // Skip unparseable payloads
      }
    }
    return false
  }

  void connect()

  return () => {
    active = false
    if (retryTimer) clearTimeout(retryTimer)
    controller.abort()
  }
}
