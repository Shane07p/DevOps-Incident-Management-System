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

  let orgSlug = ''
  let toolToken = ''
  let incidentId: number
  let adminToken = ''

  const mockInvestigation = {
    id: 101,
    summary: 'Recent deployment introduced unhandled NullPointerException in checkout payment pipeline.',
    hypotheses: [
      {
        rank: 1,
        title: 'Bad deploy introducing null pointer on checkout payment flow',
        confidence: 0.94,
        evidence: [
          { kind: 'DEPLOY', text: 'Deploy v2.4.1 completed 5 minutes before alerts triggered' },
          { kind: 'LOG', text: 'NullPointerException in PaymentProcessor.java:142' },
        ],
      },
    ],
    suggestedActions: [
      {
        id: 201,
        text: 'Rollback checkout-service to previous stable release v2.4.0',
        decision: null,
        decidedBy: null,
        decidedAt: null,
      },
    ],
    createdAt: new Date().toISOString(),
  }

  const mockDecidedAction = {
    id: 201,
    text: 'Rollback checkout-service to previous stable release v2.4.0',
    decision: 'ACCEPTED',
    decidedBy: engineerName,
    decidedAt: new Date().toISOString(),
  }

  it('executes the full incident lifecycle across roles and features', () => {
    // -------------------------------------------------------------------------
    // Setup AI service interceptions so test never waits on a real AI model
    // -------------------------------------------------------------------------
    cy.intercept('GET', '**/api/incidents/*/investigations/latest', {
      statusCode: 200,
      body: mockInvestigation,
    }).as('getLatestInvestigation')

    cy.intercept('POST', '**/api/incidents/*/investigations', {
      statusCode: 200,
      body: mockInvestigation,
    }).as('runInvestigation')

    cy.intercept('POST', '**/api/investigations/*/actions/*/decision', {
      statusCode: 200,
      body: mockDecidedAction,
    }).as('decideAction')

    // -------------------------------------------------------------------------
    // STEP 1: Setup - Admin signs up an org, adds users, and registers a service
    // -------------------------------------------------------------------------
    cy.intercept('POST', '/api/auth/signup').as('signupReq')
    cy.visit('/signup')

    cy.getByRole('heading', { name: 'Create a workspace' }).should('be.visible')
    cy.getByLabelText('Organisation').type(orgName)
    cy.getByLabelText('Your name').type(adminName)
    cy.getByLabelText('Work email').type(adminEmail)
    cy.getByLabelText('Password').type(password)
    cy.getByRole('button', { name: 'Create a workspace' }).click()

    cy.wait('@signupReq').then((interception) => {
      expect(interception.response?.statusCode).to.be.oneOf([200, 201])
      orgSlug = interception.response?.body.org.slug
      adminToken = interception.response?.body.token
      expect(orgSlug).to.be.a('string')
    })

    // Wait for authentication and redirect to home
    cy.url().should('include', '/home')

    // Admin adds Engineer and Viewer on /users
    cy.intercept('GET', '/api/users').as('getUsersReq')
    cy.intercept('POST', '/api/users').as('createUserReq')
    cy.visit('/users')
    cy.wait('@getUsersReq')
    cy.getByRole('heading', { name: 'Members' }).should('be.visible')

    // Add Engineer
    cy.getByRole('button', { name: 'Add member' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByLabelText('Email').type(engineerEmail)
      cy.getByLabelText('Display name').type(engineerName)
      cy.getByLabelText('Password').type(password)
      // Role defaults to ENGINEER
      cy.getByRole('button', { name: 'Add member' }).click()
    })
    cy.wait('@createUserReq')
    cy.contains(engineerName).should('be.visible')

    // Add Viewer
    cy.getByRole('button', { name: 'Add member' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByLabelText('Email').type(viewerEmail)
      cy.getByLabelText('Display name').type(viewerName)
      cy.getByLabelText('Password').type(password)
      cy.getByRole('combobox').click()
    })
    cy.getByRole('option', { name: 'Viewer' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('button', { name: 'Add member' }).click()
    })
    cy.wait('@createUserReq')
    cy.contains(viewerName).should('be.visible')

    // Admin registers a service with SLA targets on /services
    cy.intercept('GET', '/api/services').as('getServicesReq')
    cy.intercept('POST', '/api/services').as('createServiceReq')
    cy.visit('/services')
    cy.wait('@getServicesReq')

    cy.getByRole('button', { name: 'New service' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByLabelText('Name').type(serviceName)
      cy.getByLabelText('Description').type(serviceDescription)

      // Select owner (Engineer)
      cy.getByLabelText('Owner').click()
    })
    cy.getByRole('option', { name: engineerName }).click()

    cy.getByRole('dialog').within(() => {
      // Select criticality (High)
      cy.getByLabelText('Criticality').click()
    })
    cy.getByRole('option', { name: /high/i }).click()

    cy.getByRole('dialog').within(() => {
      // SLA Targets: Response minutes default to 15/60/240/1440. Enter resolution minutes:
      cy.getByLabelText('SEV1 resolution target, in minutes').type('30')
      cy.getByLabelText('SEV2 resolution target, in minutes').type('120')
      cy.getByLabelText('SEV3 resolution target, in minutes').type('480')
      cy.getByLabelText('SEV4 resolution target, in minutes').type('2880')

      // Public status page visibility
      cy.getByLabelText('Show this service on the status page').check()
      cy.getByLabelText('Public name').type(serviceName)

      cy.getByRole('button', { name: 'Add service' }).click()
    })
    cy.wait('@createServiceReq')
    cy.contains(serviceName).should('be.visible')

    // Admin registers a monitoring tool on /tools to get tool token
    cy.intercept('POST', '/api/tools').as('createToolReq')
    cy.visit('/tools')
    cy.getByRole('heading', { name: 'Tools' }).should('be.visible')
    cy.getByRole('button', { name: 'Register tool' }).click()

    cy.getByRole('dialog').within(() => {
      cy.getByLabelText('Name').type(`Grafana-${timestamp}`)
      cy.getByRole('button', { name: 'Register tool' }).click()
    })

    cy.wait('@createToolReq').then((interception) => {
      expect(interception.response?.statusCode).to.be.oneOf([200, 201])
      toolToken = interception.response?.body.token
      expect(toolToken).to.be.a('string')
    })
    cy.getByRole('button', { name: 'Done' }).click()

    // -------------------------------------------------------------------------
    // STEP 2: Creation - Trigger SEV2 incident by posting alert with tool token
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // STEP 3: Acknowledge - Login as Engineer, acknowledge, and become Lead
    // -------------------------------------------------------------------------
    cy.clearAllSessionStorage()
    cy.intercept('POST', '/api/auth/login').as('engineerLoginReq')
    cy.visit('/login')

    cy.getByLabelText('Work email').type(engineerEmail)
    cy.getByLabelText('Password').type(password)
    cy.getByRole('button', { name: 'Sign in' }).click()
    cy.wait('@engineerLoginReq')
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
      incidentId = Number(match![1])
    })

    // Acknowledge the incident
    cy.intercept('POST', '**/api/incidents/*/acknowledge').as('ackReq')
    cy.getByRole('button', { name: 'Acknowledge' }).should('be.visible').click()
    cy.wait('@ackReq')

    // Verify incident transitions to ACKNOWLEDGED and Engineer becomes Lead
    cy.contains('Acknowledged').should('be.visible')
    cy.contains('Lead').parent().should('contain', engineerName)

    // -------------------------------------------------------------------------
    // STEP 4: AI Investigate - Intercept AI endpoint, verify hypothesis, click Accept
    // -------------------------------------------------------------------------
    cy.get('body').then(($body) => {
      // If an Investigate tab or section exists, open it
      const tab = $body.find('[role="tab"]').filter((_, el) => /investigate/i.test(el.textContent || ''))
      if (tab.length > 0) {
        cy.wrap(tab).click()
      }

      // If "Investigate incident" button exists, trigger investigation
      const investigateBtn = $body.find('button').filter((_, el) => /investigate/i.test(el.textContent || ''))
      if (investigateBtn.length > 0 && !investigateBtn.text().includes('Investigating')) {
        cy.wrap(investigateBtn).first().click()
        cy.wait('@runInvestigation')
      }
    })

    // Verify the mock hypothesis renders
    cy.contains('Bad deploy introducing null pointer on checkout payment flow').should('be.visible')

    // Click Accept button on suggested action
    cy.getByRole('button', { name: 'Accept' }).should('be.visible').click()
    cy.wait('@decideAction')
    cy.contains('Accepted').should('be.visible')

    // -------------------------------------------------------------------------
    // STEP 5: Status Progression - Public update and sequential status moves
    // -------------------------------------------------------------------------
    // Post a public update
    cy.intercept('POST', '**/api/incidents/*/public-updates').as('postPublicUpdateReq')
    cy.getByLabelText('Public message').type(publicUpdateMessage)
    cy.getByRole('button', { name: 'Post public update' }).click()
    cy.wait('@postPublicUpdateReq')

    // Verify update appears on the public status page /status/{slug}
    cy.visit(`/status/${orgSlug}`)
    cy.contains(publicUpdateMessage).should('be.visible')

    // Return to the incident detail page
    cy.visit(`/incidents/${incidentId}`)

    // Transition status sequentially: Identified -> Monitoring -> Resolved (with recovery note)
    cy.intercept('POST', '**/api/incidents/*/status').as('changeStatusReq')

    // Move to INVESTIGATING first if required by status transition graph
    cy.getByRole('button', { name: 'Change status' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('combobox').click()
    })
    cy.getByRole('option', { name: 'INVESTIGATING' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq')

    // Move to IDENTIFIED
    cy.getByRole('button', { name: 'Change status' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('combobox').click()
    })
    cy.getByRole('option', { name: 'IDENTIFIED' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq')
    cy.contains('Identified').should('be.visible')

    // Move to MONITORING
    cy.getByRole('button', { name: 'Change status' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('combobox').click()
    })
    cy.getByRole('option', { name: 'MONITORING' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq')
    cy.contains('Monitoring').should('be.visible')

    // Move to RESOLVED with a recovery note
    cy.getByRole('button', { name: 'Change status' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('combobox').click()
    })
    cy.getByRole('option', { name: 'RESOLVED' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByLabelText('Recovery note').type(recoveryNote)
      cy.getByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq')
    cy.contains('Resolved').should('be.visible')

    // -------------------------------------------------------------------------
    // STEP 6: Postmortem - Write postmortem, review, follow-up, and close
    // -------------------------------------------------------------------------
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
      cy.request({
        method: 'POST',
        url: `/api/incidents/${incidentId}/postmortem/review`,
        headers: {
          Authorization: `Bearer ${sessionStorage.getItem('token')}`,
        },
        body: {
          expectedVersion: pmVersion,
        },
      }).then((reviewRes) => {
        expect(reviewRes.status).to.eq(200)
        expect(reviewRes.body.status).to.eq('REVIEWED')
      })
    })

    // Add follow-up action item
    const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10)
    cy.request({
      method: 'POST',
      url: `/api/incidents/${incidentId}/follow-ups`,
      headers: {
        Authorization: `Bearer ${sessionStorage.getItem('token')}`,
      },
      body: {
        title: 'Add automated integration tests for payment rollback flow',
        kind: 'PREVENTIVE',
        ownerId: 1,
        dueDate: tomorrow,
      },
    }).then((fuRes) => {
      expect(fuRes.status).to.be.oneOf([200, 201])
    })

    // Now close the incident on the detail page (allowed because postmortem is reviewed)
    cy.visit(`/incidents/${incidentId}`)
    cy.getByRole('button', { name: 'Change status' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('combobox').click()
    })
    cy.getByRole('option', { name: 'CLOSED' }).click()
    cy.getByRole('dialog').within(() => {
      cy.getByRole('button', { name: 'Change status' }).click()
    })
    cy.wait('@changeStatusReq')
    cy.contains('Closed').should('be.visible')

    // -------------------------------------------------------------------------
    // STEP 7: Insights - Verify incident is counted in MTTA and MTTR metrics
    // -------------------------------------------------------------------------
    cy.intercept('GET', '/api/insights*').as('getInsightsReq')
    cy.visit('/insights')
    cy.wait('@getInsightsReq')

    cy.getByRole('heading', { name: 'Insights' }).should('be.visible')
    cy.contains('Mean Time to Acknowledge (MTTA)').should('be.visible')
    cy.contains('Mean Time to Resolve (MTTR)').should('be.visible')
    cy.contains('Total incidents').parent().should('contain', '1')

    // -------------------------------------------------------------------------
    // STEP 8: RBAC (Viewer) - Verify read-only access and refused at /incidents/new
    // -------------------------------------------------------------------------
    cy.clearAllSessionStorage()
    cy.intercept('POST', '/api/auth/login').as('viewerLoginReq')
    cy.visit('/login')

    cy.getByLabelText('Work email').type(viewerEmail)
    cy.getByLabelText('Password').type(password)
    cy.getByRole('button', { name: 'Sign in' }).click()
    cy.wait('@viewerLoginReq')
    cy.url().should('include', '/home')

    // Viewer navigates to the incident detail page
    cy.visit(`/incidents/${incidentId}`)
    cy.contains(alertTitle).should('be.visible')

    // Verify Viewer has NO responder/action buttons
    cy.getByRole('button', { name: 'Acknowledge' }).should('not.exist')
    cy.getByRole('button', { name: 'Change status' }).should('not.exist')
    cy.getByRole('button', { name: 'Change severity' }).should('not.exist')
    cy.getByRole('button', { name: 'Reassign' }).should('not.exist')
    cy.getByRole('button', { name: 'Give a role' }).should('not.exist')

    // Try to navigate to /incidents/new -> refused and redirected back home
    cy.visit('/incidents/new')
    cy.url().should('include', '/home')
    cy.getByRole('button', { name: 'Declare incident' }).should('not.exist')

    // Log in as Engineer to test field validation errors on empty declare form
    cy.clearAllSessionStorage()
    cy.visit('/login')
    cy.getByLabelText('Work email').type(engineerEmail)
    cy.getByLabelText('Password').type(password)
    cy.getByRole('button', { name: 'Sign in' }).click()
    cy.wait('@engineerLoginReq')

    cy.visit('/incidents/new')
    cy.getByRole('heading', { name: 'Declare incident' }).should('be.visible')

    // Submit the declare form while empty
    cy.getByRole('button', { name: 'Declare incident' }).click()

    // Verify standard field validation errors are displayed
    cy.contains('Enter a title').should('be.visible')
    cy.contains('Describe what is happening').should('be.visible')
    cy.contains('Choose a service').should('be.visible')
    cy.contains('Choose a severity').should('be.visible')
  })
})
