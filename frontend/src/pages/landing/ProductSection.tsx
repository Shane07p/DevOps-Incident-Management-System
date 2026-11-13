// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function ProductSection() {
  return (
    <section className="section ground-surface" id="product" aria-labelledby="product-h">
      <div className="wrap">
        <div className="grid grid--top">
          <h2 className="span-5" id="product-h">
            Every incident and its timeline
          </h2>
          <p className="span-6 start-7 muted">
            The list sorts by urgency: waiting for acknowledgement first, then the clock closest to its target. Open an incident to read its alerts, deploys, notes and the AI's suggestion in order.
          </p>
        </div>
        <div className="ui app fill" role="img" aria-label="Settle with the open incidents listed on the left and the timeline of INC-1042 open on the right" data-ui-pause-host="">
          <div className="app__pane app__pane--list">
            <div className="ui ui-panel ui-win" data-ui="window" data-open="Open" data-work="Investigating" data-done="Resolved">
              <div className="ui-win__bar">
                <span className="ui-dots" aria-hidden="true">
                  <i />
                  <i />
                  <i />
                </span>
                <span className="ui-win__crumb">
                  {"Incidents "}
                  <i>
                    /
                  </i>
                  {' '}
                  <b>
                    Active
                  </b>
                </span>
                <span className="ui-spacer" />
                <span className="ui-win__search" aria-hidden="true">
                  <svg className="ui-icon" viewBox="0 0 16 16">
                    <circle cx="7" cy="7" r="4.5" />
                    <path d="m10.5 10.5 3 3" />
                  </svg>
                  <span className="ui-win__ph">
                    Search
                  </span>
                  <span className="ui-kbd">
                    /
                  </span>
                </span>
              </div>
              <div className="ui-win__body">
                <nav className="ui-win__side" aria-hidden="true">
                  <span className="ui-win__nav is-on">
                    <svg className="ui-icon" viewBox="0 0 16 16">
                      <path d="M2.5 9h3l1 1.5h3l1-1.5h3" />
                      <path d="M2.5 9 4 3.5h8L13.5 9v3.5h-11z" />
                    </svg>
                    Active
                    <em>
                      9
                    </em>
                  </span>
                  <span className="ui-win__nav">
                    <svg className="ui-icon" viewBox="0 0 16 16">
                      <circle cx="8" cy="6" r="2.5" />
                      <path d="M3.5 13.5c.6-2.3 2.4-3.5 4.5-3.5s3.9 1.2 4.5 3.5" />
                    </svg>
                    Mine
                    <em>
                      2
                    </em>
                  </span>
                  <span className="ui-win__nav">
                    <svg className="ui-icon" viewBox="0 0 16 16">
                      <circle cx="8" cy="8" r="5.5" />
                      <path d="M8 5v3l2 1.5" />
                    </svg>
                    Waiting
                    <em>
                      3
                    </em>
                  </span>
                  <span className="ui-win__nav">
                    <svg className="ui-icon" viewBox="0 0 16 16">
                      <path d="m3.5 8.4 2.8 2.8 6.2-6.4" />
                    </svg>
                    Resolved
                  </span>
                  <span className="ui-win__label">
                    Services
                  </span>
                  <span className="ui-win__nav">
                    <span className="ui-win__hash">
                      #
                    </span>
                    Payments
                  </span>
                  <span className="ui-win__nav">
                    <span className="ui-win__hash">
                      #
                    </span>
                    Checkout
                  </span>
                </nav>
                <div className="ui-win__main">
                  <div className="ui-win__cols" aria-hidden="true">
                    <span>
                      Lead
                    </span>
                    <span>
                      Incident
                    </span>
                    <span>
                      Status
                    </span>
                    <span>
                      Clock
                    </span>
                  </div>
                  <ul className="ui-win__rows">
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="2">
                        RS
                      </span>
                      <span className="ui-win__who">
                        Paging Rohan Shah
                      </span>
                      <span className="ui-win__sub">
                        INC-1043 · UPI authorisations failing
                      </span>
                      <span className="ui-win__st is-you" data-st="">
                        Waiting
                      </span>
                      <span className="ui-win__age">
                        3m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="4">
                        DM
                      </span>
                      <span className="ui-win__who">
                        Paging Dev Mehta
                      </span>
                      <span className="ui-win__sub">
                        INC-1040 · Order queue backlog
                      </span>
                      <span className="ui-win__st is-you" data-st="">
                        Waiting
                      </span>
                      <span className="ui-win__age">
                        3h 48m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="5">
                        KI
                      </span>
                      <span className="ui-win__who">
                        Paging Kavya Iyer
                      </span>
                      <span className="ui-win__sub">
                        INC-1045 · Search results empty
                      </span>
                      <span className="ui-win__st is-you" data-st="">
                        Waiting
                      </span>
                      <span className="ui-win__age">
                        54m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="3">
                        RS
                      </span>
                      <span className="ui-win__who">
                        Rohan Shah
                      </span>
                      <span className="ui-win__sub">
                        INC-1041 · Gateway latency, 30m over
                      </span>
                      <span className="ui-win__st" data-st="">
                        Monitoring
                      </span>
                      <span className="ui-win__age">
                        +30m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="1">
                        AP
                      </span>
                      <span className="ui-win__who">
                        Asha Patel
                      </span>
                      <span className="ui-win__sub">
                        INC-1042 · Checkout failures after deploy
                      </span>
                      <span className="ui-win__st" data-st="">
                        Investigating
                      </span>
                      <span className="ui-win__age">
                        3h 36m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="4">
                        DM
                      </span>
                      <span className="ui-win__who">
                        Dev Mehta
                      </span>
                      <span className="ui-win__sub">
                        INC-1036 · Invoice job missed heartbeat
                      </span>
                      <span className="ui-win__st" data-st="">
                        Identified
                      </span>
                      <span className="ui-win__age">
                        5h 10m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="5">
                        KI
                      </span>
                      <span className="ui-win__who">
                        Kavya Iyer
                      </span>
                      <span className="ui-win__sub">
                        INC-1035 · Cache hit rate dropped
                      </span>
                      <span className="ui-win__st" data-st="">
                        Monitoring
                      </span>
                      <span className="ui-win__age">
                        6h 02m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="1">
                        AP
                      </span>
                      <span className="ui-win__who">
                        Asha Patel
                      </span>
                      <span className="ui-win__sub">
                        INC-1033 · TLS certificate expires in 9 days
                      </span>
                      <span className="ui-win__st" data-st="">
                        Identified
                      </span>
                      <span className="ui-win__age">
                        2d 4h
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="3">
                        RS
                      </span>
                      <span className="ui-win__who">
                        Rohan Shah
                      </span>
                      <span className="ui-win__sub">
                        INC-1039 · Search indexing lag
                      </span>
                      <span className="ui-win__st is-done" data-st="">
                        Resolved
                      </span>
                      <span className="ui-win__age">
                        42m
                      </span>
                    </li>
                    <li className="ui-win__row" data-row="">
                      <span className="ui-avatar" data-tone="1">
                        AP
                      </span>
                      <span className="ui-win__who">
                        Asha Patel
                      </span>
                      <span className="ui-win__sub">
                        INC-1038 · Connection pool exhausted
                      </span>
                      <span className="ui-win__st is-done" data-st="">
                        Resolved
                      </span>
                      <span className="ui-win__age">
                        58m
                      </span>
                    </li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
          <div className="app__pane app__pane--open" data-ui-loop="">
            <div className="ui ui-panel ui-chat" data-ui="chat">
              <div className="ui-head">
                <span className="ui-agent" aria-hidden="true" />
                <div className="ui-title">
                  INC-1042 · Checkout failures
                </div>
                <span className="ui-chat__online">
                  Investigating
                </span>
                <span className="ui-spacer" />
              </div>
              <div className="ui-chat__body">
                <div className="ui-chat__day">
                  31 Oct · IST · response met in 7m · 3h 36m left to resolve
                </div>
                <div className="ui-msg ui-msg--bot">
                  <span className="ui-agent ui-msg__av" aria-hidden="true" />
                  <span className="ui-msg__col">
                    <span className="ui-chat__answer">
                      {"14:02 · Prometheus: checkout 5xx above 5% for 3 minutes. "}
                      <b>
                        18 alerts
                      </b>
                      {" share this incident's correlation key."}
                    </span>
                  </span>
                </div>
                <div className="ui-msg ui-msg--me">
                  <span className="ui-msg__text">
                    14:09 · Acknowledged. I'm Lead. Escalation stopped.
                  </span>
                </div>
                <div className="ui-msg ui-msg--bot">
                  <span className="ui-agent ui-msg__av" aria-hidden="true" />
                  <span className="ui-msg__col">
                    <span className="ui-chat__answer">
                      14:00 · Deploy a8f3c21 to checkout-api by Dev Mehta, two minutes before the first alert.
                    </span>
                  </span>
                </div>
                <div className="ui-msg ui-msg--me" data-chat-q="">
                  <span className="ui-msg__text">
                    The spike starts two minutes after deploy a8f3c21. Checking the connection pool changes against the last healthy release.
                  </span>
                </div>
                <div className="ui-msg ui-msg--bot" data-chat-a="">
                  <span className="ui-agent ui-msg__av" aria-hidden="true" />
                  <span className="ui-msg__col">
                    <span className="ui-chat__typing" aria-hidden="true">
                      <i />
                      <i />
                      <i />
                    </span>
                    <span className="ui-chat__answer">
                      {"Connection pool saturation may explain the failures. "}
                      <b>
                        a8f3c21 lowered the pool from 40 to 20
                      </b>
                      , and INC-1038 began the same way.
                    </span>
                    <span className="ui-chat__srcs">
                      <span className="ui-chat__src">
                        <svg className="ui-icon" viewBox="0 0 16 16">
                          <circle cx="5" cy="4" r="1.5" />
                          <circle cx="5" cy="12" r="1.5" />
                          <circle cx="11" cy="6" r="1.5" />
                          <path d="M5 5.5v5M11 7.5c0 2-2 2.5-6 3" />
                        </svg>
                        Deploy a8f3c21
                      </span>
                      <span className="ui-chat__src">
                        <svg className="ui-icon" viewBox="0 0 16 16">
                          <path d="M4 2.5h5l3 3v8H4z" />
                          <path d="M9 2.5v3h3" />
                        </svg>
                        INC-1038
                      </span>
                    </span>
                  </span>
                </div>
                <div className="ui-msg ui-msg--me">
                  <span className="ui-msg__text">
                    Rolling back a8f3c21 now. Error rate back under 1% at 14:31.
                  </span>
                </div>
              </div>
              <div className="ui-chat__input">
                <span className="ui-chat__ph">
                  Add an internal note
                </span>
                <span className="ui-chat__send" aria-hidden="true">
                  <svg viewBox="0 0 16 16" width="14" height="14">
                    <path d="M8 13V3.5M3.8 7.5 8 3.3l4.2 4.2" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
