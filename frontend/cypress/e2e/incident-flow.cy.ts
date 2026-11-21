describe('Complete Incident Flow (Issue #57)', () => {
  const timestamp = Date.now()
  const orgName = `Acme Corp ${timestamp}`
  const adminName = 'Admin Alice'
  const adminEmail = `admin-${timestamp}@example.com`
  const password = 'Password123!'

  const engineerName = 'Engineer Jane'
  const engineerEmail = `engineer-${timestamp}@example.com`

  const viewerName = 'Viewer Bob'
  const viewerEmail = `viewer-${timestamp}@example.com`

  const serviceName = `Checkout Service ${timestamp}`
  const serviceDescription = 'Core payment and order checkout service'
  const alertTitle = `High 5xx error rate on checkout (${timestamp})`
  const alertDescription = 'Elevated 500 error rates detected across checkout endpoints.'
  const publicUpdateMessage = `Investigating checkout payment degradation. Team is mitigating (${timestamp}).`
  const recoveryNote = 'Rolled back release v2.4.1 to v2.4.0. Error rate returned to 0%.'
  const closedIncidentComment = 'Recovery verified; follow-up work is tracked after closure.'

  let orgSlug = ''
  let toolToken = ''
  let incidentId: number
  let adminToken = ''
  let engineerToken = ''
  let engineerId: number

  it('executes the full incident lifecycle across roles and features', () => {
    // -------------------------------------------------------------------------
    // STEP 1: Setup - Admin signs up an org, adds users, and registers a service
    // -------------------------------------------------------------------------
    cy.intercept('POST', '/api/auth/signup').as('signupReq')
    cy.visit('/signup')

    cy.findByRole('heading', { name: 'Create a workspace' }).should('be.visible')
    cy.findByLabelText('Organisation').type(orgName)
    cy.findByLabelText('Your name').type(adminName)
    cy.findByLabelText('Work email').type(adminEmail)
    cy.findByLabelText('Password').type(password)
    cy.findByRole('button', { name: 'Create a workspace' }).click()

    cy.wait('@signupReq').then((interception) => {
      expect(interception.response?.statusCode).to.be.oneOf([200, 201])
      orgSlug = interception.response?.body.org.slug
      adminToken = interception.response?.body.token
      expect(orgSlug).to.be.a('string')
    })

    cy.window().then((win) => {
      if (!adminToken) {
        adminToken = win.sessionStorage.getItem('token') || ''
      }
    })

    // Wait for authentication and redirect to home
    cy.url().should('include', '/home')

    // Admin adds Engineer and Viewer on /users
    cy.intercept('GET', '/api/users').as('getUsersReq')
    cy.intercept('POST', '/api/users').as('createUserReq')
    cy.visit('/users')
    cy.wait('@getUsersReq')
    cy.findByRole('heading', { name: 'Members' }).should('be.visible')

    // Add Engineer
    cy.findByRole('button', { name: 'Add member' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByLabelText('Email').type(engineerEmail)
      cy.findByLabelText('Display name').type(engineerName)
      cy.findByLabelText('Password').type(password)
      // Role defaults to ENGINEER
      cy.findByRole('button', { name: 'Add member' }).click()
    })
    cy.wait('@createUserReq').then((interception) => {
      // Capture the Engineer's id for follow-up ownerId (Fix #5)
      engineerId = interception.response?.body.id
      expect(engineerId).to.be.a('number')
    })
    cy.get('[role="dialog"]').should('not.exist')
    cy.findByRole('table').contains(engineerName).should('be.visible')

    // Add Viewer
    cy.findByRole('button', { name: 'Add member' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByLabelText('Email').type(viewerEmail)
      cy.findByLabelText('Display name').type(viewerName)
      cy.findByLabelText('Password').type(password)
      cy.findByRole('combobox').click()
    })
    cy.findByRole('option', { name: 'Viewer' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByRole('button', { name: 'Add member' }).click()
    })
    cy.wait('@createUserReq')
    cy.get('[role="dialog"]').should('not.exist')
    cy.findByRole('table').contains(viewerName).should('be.visible')

    // Admin registers a service with SLA targets on /services
    cy.intercept('GET', '/api/services').as('getServicesReq')
    cy.intercept('POST', '/api/services').as('createServiceReq')
    cy.visit('/services')
    cy.wait('@getServicesReq')

    cy.findByRole('button', { name: 'New service' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByLabelText('Name').type(serviceName)
      cy.findByLabelText('Description').type(serviceDescription)

      // Select owner (Engineer)
      cy.findByLabelText('Owner').click()
    })
    cy.findByRole('option', { name: engineerName }).click()

    cy.findByRole('dialog').within(() => {
      // Select criticality (High)
      cy.findByLabelText('Criticality').click()
    })
    cy.findByRole('option', { name: /high/i }).click()

    cy.findByRole('dialog').within(() => {
      // SLA Targets: Response minutes default to 15/60/240/1440. Enter resolution minutes:
      cy.findByLabelText('SEV1 resolution target, in minutes').type('30')
      cy.findByLabelText('SEV2 resolution target, in minutes').type('120')
      cy.findByLabelText('SEV3 resolution target, in minutes').type('480')
      cy.findByLabelText('SEV4 resolution target, in minutes').type('2880')

      // Public status page visibility
      cy.findByLabelText('Show this service on the status page').click().should('have.attr', 'aria-checked', 'true')
      cy.findByLabelText('Public name').type(serviceName)

      cy.findByRole('button', { name: 'Add service' }).click()
    })
    cy.wait('@createServiceReq')
    cy.contains(serviceName).should('be.visible')

    // Admin registers a monitoring tool on /tools to get tool token
    cy.intercept('POST', '/api/tools').as('createToolReq')
    cy.visit('/tools')
    cy.findByRole('heading', { name: 'Monitoring tools' }).should('be.visible')
    cy.findByRole('button', { name: 'Register tool' }).click()

    cy.findByRole('dialog').within(() => {
      cy.findByLabelText('Name').type(`Grafana-${timestamp}`)
      cy.findByRole('button', { name: 'Register tool' }).click()
    })

    cy.wait('@createToolReq').then((interception) => {
      expect(interception.response?.statusCode).to.be.oneOf([200, 201])
      toolToken = interception.response?.body.token
      expect(toolToken).to.be.a('string')
    })
    cy.findByRole('button', { name: 'Done' }).click()

    // -------------------------------------------------------------------------
    // STEP 2: Creation - Trigger SEV2 incident by posting alert with tool token
    // -------------------------------------------------------------------------
    cy.then(() => {
      cy.request({
        method: 'POST',
        url: '/webhooks/alerts',
        headers: {
          Authorization: `Bearer ${toolToken}`,
        },
        body: {
          source: 'grafana',
          eventId: `alert-evt-${timestamp}`,
          service: serviceName,
          title: alertTitle,
          description: alertDescription,
          severity: 'SEV2',
          correlationKey: `corr-${timestamp}`,
        },
      }).then((response) => {
        expect(response.status).to.eq(202)
      })
    })

    // -------------------------------------------------------------------------
    // Check Viewer permissions while the incident is OPEN, when responders have actions.
    cy.clearAllSessionStorage()
    cy.intercept('POST', '/api/auth/login').as('activeViewerLoginReq')
    cy.visit('/login')
    cy.findByLabelText('Work email').type(viewerEmail)
    cy.findByLabelText('Password').type(password)
    cy.findByRole('button', { name: 'Sign in' }).click()
    cy.wait('@activeViewerLoginReq').its('response.statusCode').should('eq', 200)
    cy.url().should('include', '/home')

    cy.intercept('GET', '/api/incidents').as('activeViewerIncidentsReq')
    cy.visit('/incidents')
    cy.wait('@activeViewerIncidentsReq')
    cy.findByRole('link', { name: alertTitle }).click()
    cy.url().should('match', /\/incidents\/\d+/).then((url) => {
      incidentId = Number(url.match(/\/incidents\/(\d+)/)![1])
    })
    cy.findByRole('heading', { name: alertTitle }).should('be.visible')
    cy.findByRole('region', { name: 'Timeline' }).within(() => {
      cy.findByRole('list').should('be.visible')
    })
    cy.findByRole('button', { name: 'Change status' }).should('not.exist')
    cy.findByRole('button', { name: 'Acknowledge' }).should('not.exist')
    cy.findByRole('button', { name: /^(Assign|Reassign|Give a role)$/ }).should('not.exist')
    cy.findByRole('button', { name: 'Add evidence' }).should('not.exist')

    // STEP 3: Acknowledge - Login as Engineer, acknowledge, and become Lead
    // -------------------------------------------------------------------------
    cy.clearAllSessionStorage()
    cy.intercept('POST', '/api/auth/login').as('engineerLoginReq')
    cy.visit('/login')

    cy.findByLabelText('Work email').type(engineerEmail)
    cy.findByLabelText('Password').type(password)
    cy.findByRole('button', { name: 'Sign in' }).click()
    cy.wait('@engineerLoginReq').then((interception) => {
      // Capture the Engineer's token from the login response (Fix #4)
      engineerToken = interception.response?.body.token
    })
    cy.window().then((win) => {
      if (!engineerToken) {
        engineerToken = win.sessionStorage.getItem('token') || ''
      }
    })
    cy.url().should('include', '/home')

    // Navigate to /incidents and find the declared incident
    cy.intercept('GET', '/api/incidents').as('listIncidentsReq')
    cy.visit('/incidents')
    cy.wait('@listIncidentsReq')

    cy.contains(alertTitle).should('be.visible').click()
    cy.url().should('match', /\/incidents\/\d+/)

    cy.url().then((url) => {
      const match = url.match(/\/incidents\/(\d+)/)
      assert.isNotNull(match)
      expect(Number(match![1])).to.eq(incidentId)
    })

    // Acknowledge the incident
    cy.intercept('POST', '**/api/incidents/*/acknowledge').as('ackReq')
    cy.findByRole('button', { name: 'Acknowledge' }).should('be.visible').click()
    cy.wait('@ackReq')

    // Verify incident transitions to ACKNOWLEDGED and Engineer becomes Lead
    cy.contains('Acknowledged').should('be.visible')
    cy.contains('Lead').parent().should('contain', engineerName)

    // -------------------------------------------------------------------------
    // STEP 4: AI Investigate — SKIPPED
    // The AI investigation features (hypothesis display, Accept action) depend
    // on #242 (PR #257) and #249 (PR #271), which have not been merged into
    // main as of the 15 Nov cut-off specified by #57.  This step will be added
    // once those PRs land.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // STEP 5: Status Progression - Public update and sequential status moves
    // -------------------------------------------------------------------------
    // Post a public update
    cy.intercept('POST', '**/api/incidents/*/public-updates').as('postPublicUpdateReq')
    cy.findByLabelText('Public message').type(publicUpdateMessage)
    cy.findByRole('button', { name: 'Post public update' }).click()
    cy.wait('@postPublicUpdateReq')

    // Verify update appears on the public status page /status/{slug}
    cy.then(() => {
      cy.visit(`/status/${orgSlug}`)
    })
    cy.contains(publicUpdateMessage).should('be.visible')

    // Return to the incident detail page
    cy.then(() => {
      cy.visit(`/incidents/${incidentId}`)
    })

    // Transition status sequentially: Investigating -> Identified -> Monitoring -> Resolved
    // The app posts to /api/incidents/{id}/transitions (Fix #2)
    cy.intercept('POST', '**/api/incidents/*/transitions').as('changeStatusReq')

    // Move to INVESTIGATING first if required by status transition graph
    cy.findByRole('button', { name: 'Change status' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByRole('combobox').click()
    })
    cy.findByRole('option', { name: 'INVESTIGATING' }).focus().type('{enter}')
    cy.get('[role="listbox"]').should('not.exist')
    cy.findByRole('dialog').within(() => {
      cy.findByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq').its('response.statusCode').should('eq', 200)
    cy.get('[role="dialog"]').should('not.exist')

    // Move to IDENTIFIED
    cy.findByRole('button', { name: 'Change status' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByRole('combobox').click()
    })
    cy.findByRole('option', { name: 'IDENTIFIED' }).focus().type('{enter}')
    cy.get('[role="listbox"]').should('not.exist')
    cy.findByRole('dialog').within(() => {
      cy.findByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq').its('response.statusCode').should('eq', 200)
    cy.get('[role="dialog"]').should('not.exist')
    cy.contains('Identified').should('be.visible')

    // Move to MONITORING
    cy.findByRole('button', { name: 'Change status' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByRole('combobox').click()
    })
    cy.findByRole('option', { name: 'MONITORING' }).focus().type('{enter}')
    cy.get('[role="listbox"]').should('not.exist')
    cy.findByRole('dialog').within(() => {
      cy.findByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq').its('response.statusCode').should('eq', 200)
    cy.get('[role="dialog"]').should('not.exist')
    cy.contains('Monitoring').should('be.visible')

    // Move to RESOLVED with a recovery note
    cy.findByRole('button', { name: 'Change status' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByRole('combobox').click()
    })
    cy.findByRole('option', { name: 'RESOLVED' }).focus().type('{enter}')
    cy.get('[role="listbox"]').should('not.exist')
    cy.findByRole('dialog').within(() => {
      cy.findByLabelText('Recovery note').type(recoveryNote)
      cy.findByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq').its('response.statusCode').should('eq', 200)
    cy.get('[role="dialog"]').should('not.exist')
    cy.contains('Resolved').should('be.visible')

    // -------------------------------------------------------------------------
    // STEP 6: Postmortem - Write postmortem, review, follow-up, and close
    // The API-based postmortem step is retained under #57's 15 Nov cutoff
    // exception and the prior review on this PR.
    // -------------------------------------------------------------------------
    cy.then(() => {
      // Create and save postmortem draft
      cy.request({
        method: 'PUT',
        url: `/api/incidents/${incidentId}/postmortem`,
        headers: {
          Authorization: `Bearer ${adminToken}`,
        },
        body: {
          summary: 'Elevated checkout 5xx errors due to deploy defect.',
          impact: '5% of checkout requests failed.',
          rootCause: 'Null pointer exception in deployment v2.4.1.',
          resolution: recoveryNote,
          wentWell: 'Alerts triggered promptly within minutes.',
          wentBadly: 'Insufficient test coverage for payment edge cases.',
          expectedVersion: null,
        },
      }).then((pmRes) => {
        expect(pmRes.status).to.be.oneOf([200, 201])
        const pmVersion = pmRes.body.version

        // Someone other than the author (e.g. Engineer) reviews the postmortem (D17 rule)
        // Use the Engineer token captured from the login response (Fix #4)
        cy.request({
          method: 'POST',
          url: `/api/incidents/${incidentId}/postmortem/review`,
          headers: {
            Authorization: `Bearer ${engineerToken}`,
          },
          body: {
            expectedVersion: pmVersion,
          },
        }).then((reviewRes) => {
          expect(reviewRes.status).to.eq(200)
          expect(reviewRes.body.status).to.eq('REVIEWED')
        })

        // Add follow-up action item
        // Use Engineer token and Engineer ID from earlier responses (Fixes #4, #5)
        const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10)
        cy.request({
          method: 'POST',
          url: `/api/incidents/${incidentId}/follow-ups`,
          headers: {
            Authorization: `Bearer ${engineerToken}`,
          },
          body: {
            title: 'Add automated integration tests for payment rollback flow',
            kind: 'PREVENTIVE',
            ownerId: engineerId,
            dueDate: tomorrow,
          },
        }).then((fuRes) => {
          expect(fuRes.status).to.be.oneOf([200, 201])
        })
      })
    })

    // Now close the incident on the detail page (allowed because postmortem is reviewed)
    cy.then(() => {
      cy.visit(`/incidents/${incidentId}`)
    })
    cy.findByRole('button', { name: 'Change status' }).click()
    cy.findByRole('dialog').within(() => {
      cy.findByRole('combobox').click()
    })
    cy.findByRole('option', { name: 'CLOSED' }).focus().type('{enter}')
    cy.get('[role="listbox"]').should('not.exist')
    cy.findByRole('dialog').within(() => {
      cy.findByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq').its('response.statusCode').should('eq', 200)
    cy.get('[role="dialog"]').should('not.exist')
    cy.contains('Closed').should('be.visible')

    // Engineers can still comment after closure; the Viewer check below must distinguish roles.
    cy.intercept('POST', '**/api/incidents/*/comments').as('closedCommentReq')
    cy.findByRole('textbox', { name: 'Add a comment' }).type(closedIncidentComment)
    cy.findByRole('button', { name: 'Post comment' }).click()
    cy.wait('@closedCommentReq').its('response.statusCode').should('eq', 201)
    cy.findByRole('region', { name: 'Timeline' }).should('contain', closedIncidentComment)

    // -------------------------------------------------------------------------
    // STEP 7: Insights - Verify incident is counted in MTTA and MTTR metrics
    // -------------------------------------------------------------------------
    cy.intercept('GET', '/api/insights*').as('getInsightsReq')
    cy.visit('/insights')
    cy.wait('@getInsightsReq').then((interception) => {
      // The org is new, so this single incident is the only data source.
      // Verify the API response carries non-null MTTA and MTTR means (Fix #6).
      const body = interception.response?.body
      expect(body.mtta.mean).to.not.be.null
      expect(body.mtta.mean).to.be.a('number')
      expect(body.mttr.mean).to.not.be.null
      expect(body.mttr.mean).to.be.a('number')
      expect(body.incidents.total).to.be.gte(1)
    })

    cy.findByRole('heading', { name: 'Insights' }).should('be.visible')
    cy.contains('Mean Time to Acknowledge (MTTA)').should('be.visible')
    cy.contains('Mean Time to Resolve (MTTR)').should('be.visible')

    // -------------------------------------------------------------------------
    // STEP 8: Viewer still reads the closed incident but cannot comment or declare incidents.
    // -------------------------------------------------------------------------
    cy.clearAllSessionStorage()
    cy.intercept('POST', '/api/auth/login').as('viewerLoginReq')
    cy.visit('/login')

    cy.findByLabelText('Work email').type(viewerEmail)
    cy.findByLabelText('Password').type(password)
    cy.findByRole('button', { name: 'Sign in' }).click()
    cy.wait('@viewerLoginReq')
    cy.url().should('include', '/home')

    // Viewer navigates to the incident detail page
    cy.then(() => {
      cy.visit(`/incidents/${incidentId}`)
    })
    cy.contains(alertTitle).should('be.visible')

    cy.findByRole('region', { name: 'Timeline' }).should('contain', closedIncidentComment)
    cy.findByRole('textbox', { name: 'Add a comment' }).should('not.exist')
    cy.findByRole('button', { name: 'Post comment' }).should('not.exist')

    // Try to navigate to /incidents/new -> refused and redirected back home
    cy.visit('/incidents/new')
    cy.url().should('include', '/home')
    cy.findByRole('button', { name: 'Declare incident' }).should('not.exist')

    // Log in as Engineer to test field validation errors on empty declare form
    cy.clearAllSessionStorage()
    cy.intercept('POST', '/api/auth/login').as('engineerLoginReq2')
    cy.visit('/login')
    cy.findByLabelText('Work email').type(engineerEmail)
    cy.findByLabelText('Password').type(password)
    cy.findByRole('button', { name: 'Sign in' }).click()
    cy.wait('@engineerLoginReq2')

    cy.visit('/incidents/new')
    cy.findByRole('heading', { name: 'Declare incident' }).should('be.visible')

    // Submit the declare form while empty
    cy.findByRole('button', { name: 'Declare incident' }).click()

    // Verify standard field validation errors are displayed
    cy.contains('Enter a title').should('be.visible')
    cy.contains('Describe what is happening').should('be.visible')
    cy.contains('Choose a service').should('be.visible')
    cy.contains('Choose a severity').should('be.visible')
  })
})
