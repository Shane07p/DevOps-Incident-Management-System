# Frontend

React 19 + TypeScript on Vite, for the Incident Management System. See the
[root README](../README.md) for the full stack and [AGENTS.md](../AGENTS.md) for the rules that
apply to changes here.

## Run

```bash
npm install
npm run dev     # http://localhost:5173
```

`/api` is proxied to `http://localhost:8080`, so start the backend separately. There is no CORS
configuration to maintain.

## Scripts

| Command | Does |
| --- | --- |
| `npm run dev` | Dev server with hot reload |
| `npm run build` | Typecheck and produce `dist/` |
| `npm run typecheck` | Types only, no output |
| `npm run lint` | oxlint |
| `npm test` | Vitest, once |
| `npm run test:watch` | Vitest, watching |
| `npm run test:coverage` | Vitest with v8 coverage |
| `npm run cypress:open` | Cypress, interactive |
| `npm run cypress:run` | Cypress, headless |

Cypress needs both the dev server and the backend running.

## Layout

| Path | Contents |
| --- | --- |
| `src/` | Application code |
| `src/test/setup.ts` | Vitest setup; registers jest-dom matchers |
| `cypress/e2e/` | The end-to-end scenario |

State that comes from the server belongs in TanStack Query, not in component state. Validate
API responses with Zod at the boundary. The backend re-checks every rule regardless, so
validation here is for the user's benefit, not for safety.
