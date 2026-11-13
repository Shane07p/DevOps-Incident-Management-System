import { useEffect, useLayoutEffect, useState } from 'react'

/*
 * The landing is the redesign preview's page, built on the taste-skill kit in public/landing: the
 * kit's stylesheets and scripts give it its layout, the fluted-glass hero, the pinned incident
 * chapters, the liquid type, the diagrams and the hover motion. This page adds them on arrival and
 * takes the stylesheets and page settings away again when it leaves, so the app never sees them.
 * Its links are plain links, so leaving the landing loads the next page fresh.
 */
const KIT = '/landing/'

// The preview's order: the kit first, the page's own styles last.
export const LANDING_STYLES = [
  'assets/system.css',
  'assets/ui/cards.css',
  'assets/motion/motion.css',
  'assets/backgrounds/backgrounds.css',
  'assets/layout/layout.css',
  'assets/icons/icon.css',
  'assets/ui/base.css',
  'assets/ui/notify/fragment.css',
  'assets/ui/window/fragment.css',
  'assets/ui/chat/fragment.css',
  'assets/diagrams/diagram.css',
  'landing.css',
]
const MODULES = ['assets/system.js', 'assets/motion/motion.js', 'assets/layout/layout.js', 'assets/diagrams/mount.js']

// What the preview sets on <html> and in <head>; the kit reads them to pick its software page, sand ground and buttons.
const HTML_ATTRIBUTES: Record<string, string> = {
  'data-type': 'software',
  'data-radius': 'soft',
  'data-ground': 'sand',
  'data-buttons': 'solid',
  'data-motion': '',
}
const METAS: Record<string, string> = { 'site-kind': 'software', 'page-type': 'software' }

declare global {
  interface Window {
    __settleLandingKit?: boolean
  }
}

function addStylesheet(href: string) {
  const link = document.createElement('link')
  link.rel = 'stylesheet'
  link.href = KIT + href
  link.dataset.landing = ''
  const loaded = new Promise<void>((resolve) => {
    link.onload = () => resolve()
    link.onerror = () => resolve()
  })
  document.head.append(link)
  return { link, loaded }
}

// Scripts go in as <script> tags, as in the preview: the kit's files are plain files, not part of the app's bundle.
function addScript(src: string, type?: 'module') {
  return new Promise<void>((resolve) => {
    const script = document.createElement('script')
    if (type) script.type = type
    script.src = KIT + src
    script.dataset.landing = ''
    script.onload = () => resolve()
    script.onerror = () => resolve()
    document.body.append(script)
  })
}

/** Adds the kit's styles and page settings, and reports when the styles have loaded. */
export function useLandingKit() {
  const [styled, setStyled] = useState(false)

  useLayoutEffect(() => {
    // The kit's scripts start once per page load. Coming back to the landing inside the app would leave
    // them asleep, so a second visit loads the landing as a fresh page instead.
    if (window.__settleLandingKit) {
      window.location.reload()
      return
    }
    const root = document.documentElement
    for (const [name, value] of Object.entries(HTML_ATTRIBUTES)) root.setAttribute(name, value)
    root.classList.add('m-enter', 'mo-js')
    const metas = Object.entries(METAS).map(([name, content]) => {
      const meta = document.createElement('meta')
      meta.name = name
      meta.content = content
      document.head.append(meta)
      return meta
    })
    const sheets = LANDING_STYLES.map(addStylesheet)
    let cancelled = false
    void Promise.all(sheets.map((sheet) => sheet.loaded)).then(() => {
      if (!cancelled) setStyled(true)
    })
    return () => {
      cancelled = true
      for (const name of Object.keys(HTML_ATTRIBUTES)) root.removeAttribute(name)
      root.classList.remove('m-enter', 'm-enter-done', 'mo-js', 'mo-safe')
      delete root.dataset.ready
      metas.forEach((meta) => meta.remove())
      sheets.forEach((sheet) => sheet.link.remove())
      document.querySelectorAll('script[data-landing]').forEach((script) => script.remove())
    }
  }, [])

  // Once the page is styled, the hero enters and the kit's scripts start, in the preview's order.
  useEffect(() => {
    if (!styled || window.__settleLandingKit) return
    window.__settleLandingKit = true
    const root = document.documentElement
    const entered = window.setTimeout(() => root.classList.add('m-enter-done'), 700)
    const safe = window.setTimeout(() => root.classList.add('mo-safe'), 2500)
    void (async () => {
      await addScript('assets/backgrounds/backgrounds.js')
      for (const module of MODULES) await addScript(module, 'module')
    })()
    return () => {
      window.clearTimeout(entered)
      window.clearTimeout(safe)
    }
  }, [styled])

  return styled
}
