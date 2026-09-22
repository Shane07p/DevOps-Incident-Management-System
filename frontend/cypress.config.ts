import { defineConfig } from 'cypress'

export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:5173',
    supportFile: 'cypress/support/e2e.ts',
    // One focused end-to-end scenario, per the test plan; not a second test suite.
    specPattern: 'cypress/e2e/**/*.cy.ts',
  },
})
