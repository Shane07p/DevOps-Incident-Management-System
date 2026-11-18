import axe from 'axe-core'
import { expect } from 'vitest'

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
