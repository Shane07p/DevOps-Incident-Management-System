# Accessibility evidence — PR #294 / Issue #251

- Date: 2026-11-20 (UTC timestamp recorded in the JSON).
- Tested source: `b68579ce07c615c51c903cef41be16224515347b`.
- Browser: Microsoft Edge 158.0.4370.33, headless, 1440 × 1100, reduced motion.
- Tool: axe-core 4.14.1, `color-contrast` rule, against the local Vite frontend.
- Data: synthetic Engineer/org/incidents/services, matching the Vitest accessibility fixtures.
  Full fixtures, measurements, passed nodes, and focus results are in
  [edge-axe-2026-11-20.json](edge-axe-2026-11-20.json). No production data or credentials were used.

| View | Light violations | Dark violations |
| --- | --- | --- |
| Home `/home` | 0 | 0 |
| Incidents `/incidents` | 0 | 0 |
| Incident `/incidents/7` | 0 | 0 |
| Services `/services` | 0 | 0 |
| Public status `/status/acme` | 0 | 0 |

All ten scans also have zero incomplete checks. Normal text requires 4.5:1; large text requires 3:1.
The active sidebar's light foreground changed from `#a4532a` to `#934823`: 4.0:1 → 4.77:1
on its rendered tint. Its dark foreground remains `#ee936b`: 6.81:1.
Resolved/closed text changed from `#4e7946` to `#426b3b`: the public-status subtitle is
4.21:1 → 5.14:1. The dark resolved foreground remains `#a5c79b`: 9.09:1.
The light focus-ring token follows the primary colour (`#a4532a` → `#934823`).

Home keyboard traversal passed 26 visible desktop controls, including all four main-page controls,
in forward and reverse DOM order, with rendered outline/box-shadow indicators checked at each stop.
Vitest covers 27 stops because jsdom cannot apply the responsive CSS hiding the mobile menu button
on desktop. Change status also trapped Tab and returned focus on Escape after keyboard and mouse opening.
At 390 × 900, the navigation drawer passed a complete cycle of its 17 stops in each direction,
including wraparound and focus restoration to the menu button on Escape.

To repeat the browser measurement, run this source commit with `cd frontend && npm ci && npm run dev`,
mock `/api` from the JSON's `fixtures`, and load each view in Edge at the recorded viewport.
Inject `frontend/node_modules/axe-core/axe.min.js`, select each theme, and run
`axe.run(document.body, {runOnly: {type: 'rule', values: ['color-contrast']}})`.
The local audit used port 5294 and a synthetic session token. jsdom checks structure and keyboard
behaviour; it cannot measure rendered contrast, so these contrast results come from Edge.
