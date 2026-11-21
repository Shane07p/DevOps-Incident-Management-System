import axe from 'axe-core'
import { expect } from 'vitest'

/**
 * Runs axe-core in jsdom to detect serious and critical accessibility violations.
 *
 * Note: 'color-contrast' is disabled because jsdom lacks layout and rendering engines
 * to compute CSS colors, background layers, and opacity. Color contrast is verified
 * in a real browser using axe DevTools / Lighthouse audits across light and dark themes.
 */
export async function expectNoSeriousAxeViolations(root: Element = document.body) {
  const { violations } = await axe.run(root, { rules: { 'color-contrast': { enabled: false } } })
  const serious = violations.filter(({ impact }) => impact === 'serious' || impact === 'critical')
  expect(
    serious.map(({ id, help, nodes }) => ({
      id,
      help,
      elements: nodes.map(({ target }) => target.join(' ')),
    })),
  ).toEqual([])
}

