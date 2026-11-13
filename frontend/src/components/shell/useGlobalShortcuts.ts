import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'

/** "G then <key>" jumps. The letter is the page's first letter, as in the Settle preview. */
export const GO_TO: { key: string; path: string; label: string }[] = [
  { key: 'o', path: '/home', label: 'Overview' },
  { key: 'i', path: '/incidents', label: 'Incidents' },
  { key: 'a', path: '/alerts', label: 'Alerts' },
  { key: 's', path: '/services', label: 'Services' },
]

// How long a "G" waits for its second key.
const JUMP_WINDOW_MS = 1200

export const modKey = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl'

function isTyping(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}

// Something already sits on top of the page (a dialog, a menu, an open select): single keys belong to it.
const somethingIsOpen = () => document.querySelector('[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]') !== null

/**
 * The app-wide keys: Ctrl/⌘+K toggles search; when nobody is typing and nothing is open, "/" searches, "?" lists
 * the shortcuts, "C" declares an incident (only when `onDeclare` is given), and G then a letter opens a page.
 */
export function useGlobalShortcuts({
  onToggleSearch,
  onShortcuts,
  onDeclare,
}: {
  onToggleSearch: () => void
  onShortcuts: () => void
  onDeclare?: () => void
}) {
  const navigate = useNavigate()
  const jumpStartedAt = useRef(0)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        onToggleSearch()
        return
      }
      if (event.metaKey || event.ctrlKey || event.altKey || event.repeat) return
      if (isTyping(event.target) || somethingIsOpen()) return

      const key = event.key.toLowerCase()
      if (Date.now() - jumpStartedAt.current < JUMP_WINDOW_MS) {
        jumpStartedAt.current = 0
        const target = GO_TO.find((page) => page.key === key)
        if (target) {
          event.preventDefault()
          navigate(target.path)
        }
        return
      }
      if (event.key === '?') {
        event.preventDefault()
        onShortcuts()
      } else if (event.key === '/') {
        event.preventDefault()
        onToggleSearch()
      } else if (key === 'g') {
        jumpStartedAt.current = Date.now()
      } else if (key === 'c' && onDeclare) {
        event.preventDefault()
        onDeclare()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [navigate, onToggleSearch, onShortcuts, onDeclare])
}
