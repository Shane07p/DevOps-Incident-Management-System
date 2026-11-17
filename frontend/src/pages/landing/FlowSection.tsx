import type { CSSProperties } from 'react'

// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function FlowSection() {
  return (
    <section className="section ground-surface" id="flow" style={{ "--h": "2" } as CSSProperties} data-moment="pinned-chapters" aria-labelledby="flow-h">
      <div className="wrap mo-pc__grid">
        <div className="mo-pc__text">
          <h2 id="flow-h">
            How an incident moves through Settle
          </h2>
          <ol className="mo-pc__steps">
            <li className="mo-pc__step">
              <h3>
                Acknowledge and take Lead
              </h3>
              <p>
                One click stops the response clock and the escalation. The resolution clock keeps running until the incident is resolved.
              </p>
              <figure className="mo-pc__visual">
                <div className="panel shot" role="img" aria-label="Settle's list of incidents waiting for acknowledgement. INC-1043, SEV1, has 3 minutes left on its response clock and an Acknowledge and take Lead button.">
                  <div className="ui app-card ack-card">
                    <div className="ac-head">
                      <b>
                        Waiting for acknowledgement
                      </b>
                      <span className="ac-count">
                        2
                      </span>
                    </div>
                    <div className="ac-row">
                      <span className="ac-sev ac-sev--1">
                        SEV1
                      </span>
                      <div className="ac-main">
                        <b>
                          UPI payment authorisations failing
                        </b>
                        <span>
                          {"INC-1043 · Payments · "}
                          <em>
                            Paging Rohan Shah
                          </em>
                        </span>
                      </div>
                      <div className="ac-side">
                        <span className="ac-clock ac-clock--risk">
                          <i />
                          {"At risk "}
                          <code>
                            3m left
                          </code>
                        </span>
                        <span className="ac-btn">
                          Acknowledge and take Lead
                        </span>
                      </div>
                    </div>
                    <div className="ac-row">
                      <span className="ac-sev">
                        SEV3
                      </span>
                      <div className="ac-main">
                        <b>
                          Order queue backlog above threshold
                        </b>
                        <span>
                          {"INC-1040 · Order Fulfillment · "}
                          <em>
                            Paging Dev Mehta
                          </em>
                        </span>
                      </div>
                      <div className="ac-side">
                        <span className="ac-clock">
                          {"Response "}
                          <code>
                            3h 48m left
                          </code>
                        </span>
                        <span className="ac-btn">
                          Acknowledge and take Lead
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              </figure>
            </li>
            <li className="mo-pc__step">
              <h3>
                Read the incident's timeline and evidence
              </h3>
              <p>
                Deploys, metrics and alerts for that service and time window sit on the incident. The AI suggests a cause and shows what it used.
              </p>
              <figure className="mo-pc__visual">
                <div className="panel shot" role="img" aria-label="An incident in Settle: the response target met in 7 minutes, 3 hours 36 minutes left to resolve, and the AI's suggested cause with its evidence.">
                  <div className="shot-pair">
                    <div className="ui app-card sla-card">
                      <b className="sc-title">
                        Service-level targets
                      </b>
                      <div className="sc-block">
                        <span>
                          {"Response "}
                          <em className="sc-tag sc-tag--met">
                            Met · stopped
                          </em>
                        </span>
                        <code>
                          7m
                        </code>
                        <i className="sc-bar">
                          <u style={{ width: "12%" }} />
                        </i>
                        <small>
                          Acknowledged in 7m · target 1h
                        </small>
                      </div>
                      <div className="sc-block">
                        <span>
                          {"Resolution "}
                          <em className="sc-tag">
                            Running
                          </em>
                        </span>
                        <code>
                          3h 36m left
                        </code>
                        <i className="sc-bar">
                          <u style={{ width: "10%" }} />
                        </i>
                        <small>
                          Resolve by 18:02 IST
                        </small>
                      </div>
                    </div>
                    <div className="ui app-card ai-card">
                      <span className="ai-label">
                        AI suggestion
                      </span>
                      <p>
                        Connection pool saturation may explain the checkout failures.
                      </p>
                      <span className="ai-sub">
                        Supporting evidence
                      </span>
                      <ul>
                        <li>
                          Deploy a8f3c21 lowered the pool from 40 to 20
                        </li>
                        <li>
                          INC-1038 started the same way
                        </li>
                      </ul>
                      <span className="ai-sub">
                        Still to verify
                      </span>
                      <ul className="ai-open">
                        <li>
                          No pool metrics captured yet
                        </li>
                      </ul>
                      <div className="ai-actions">
                        <span className="ac-btn ac-btn--ghost">
                          Accept
                        </span>
                        <span className="ac-btn ac-btn--ghost">
                          Reject
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              </figure>
            </li>
            <li className="mo-pc__step">
              <h3>
                Resolve the incident with notes
              </h3>
              <p>
                Resolving asks what was verified. SEV1 and SEV2 incidents close only after a reviewed postmortem.
              </p>
              <figure className="mo-pc__visual">
                <div className="panel shot" role="img" aria-label="The Resolve incident dialog in Settle asking for recovery notes before the resolution clock stops.">
                  <div className="ui app-card dialog-card">
                    <b className="dc-title">
                      Resolve incident
                    </b>
                    <p>
                      Confirm that recovery has been verified. This stops the resolution clock.
                    </p>
                    <span className="dc-label">
                      Recovery notes
                    </span>
                    <div className="dc-field">
                      Rolled back a8f3c21. Checkout errors under 1% for 15 minutes.
                    </div>
                    <small>
                      SEV1 and SEV2 incidents close after a reviewed postmortem.
                    </small>
                    <div className="dc-actions">
                      <span className="ac-btn ac-btn--ghost">
                        Cancel
                      </span>
                      <span className="ac-btn">
                        Resolve incident
                      </span>
                    </div>
                  </div>
                </div>
              </figure>
            </li>
          </ol>
        </div>
        <div className="mo-pc__visuals frame" />
      </div>
    </section>
  )
}
