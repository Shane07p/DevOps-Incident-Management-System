# Frontend rules

React 19 with TypeScript, Vite, TanStack Query, Zod, and Tailwind. The project-wide rules are in
the root `AGENTS.md`; commands and component sources are in `README.md`. Run `npm test`,
`npm run build`, and `npm run lint` before every pull request.

- **Install with `npm ci`**, which follows the lockfile exactly. Use `npm install` only to change
  dependencies. If `package-lock.json` conflicts in a merge, take either side and run
  `npm install` to regenerate it; never edit it by hand.
- **Layout:** pages in `src/pages/`, the app shell in `components/shell/`, Settle pieces in
  `components/app/`, shadcn primitives in `components/ui/`, animated third-party components in
  `components/effects/`.
- **Add components with the shadcn CLI**, including React Bits, Aceternity, and Magic UI through
  their registries (`README.md`). Edit `components/ui/` only for a project-wide change.
- **Style with the design tokens** from `src/index.css` (`bg-sev-1-bg`, `text-status-open`), never
  raw colours.
- **Every API response goes through `apiFetch` with a Zod schema** in `src/api/`.
- **Form inputs use the field name as their `id`**, so labels and tests find them.
- **The UI only hides controls.** The server checks every permission, so a hidden button is not
  security.
- **Times show in IST** through the helpers in `src/lib/time.ts` (`formatIst`); the API sends
  and receives UTC.
- **Vitest covers meaningful behaviour**: form validation, filters, role-dependent controls. One
  complete incident flow is a single Cypress scenario (#57); don't add more end-to-end tests.
