/** What the sign-in page reads from a work email while it is typed, for the product wall (#267). */

const titleCase = (w: string) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()

/** "asha.patel@acme.example" gives "Asha Patel". */
export function nameFromEmail(email: string) {
  return (email.split('@')[0] || '')
    .split(/[._-]+/)
    .map((p) => p.replace(/[^a-z]/gi, ''))
    .filter(Boolean)
    .slice(0, 2)
    .map(titleCase)
    .join(' ')
}

/** "asha.patel@acme.example" gives "Acme". */
export function orgFromEmail(email: string) {
  const domain = ((email.split('@')[1] || '').split('.')[0] || '').replace(/[^a-z0-9]/gi, '')
  return domain ? titleCase(domain) : ''
}

/** How long the wall takes to turn to face you before the app opens; reduced motion skips the turn. */
export function settleDelay() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 150 : 1100
}
