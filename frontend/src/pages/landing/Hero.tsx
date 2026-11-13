import type { CSSProperties } from 'react'

// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function Hero() {
  return (
    <section className="hero hero--split" data-enter="" aria-labelledby="hero-h">
      <div className="wrap">
        <div className="hero-text grid hero__grid">
          <div className="hero__text span-5">
            <h1 id="hero-h">
              Incident management for on-call teams
            </h1>
            <p className="hero__lead">
              Alerts from Grafana and your own uptime checks open one incident, page whoever is on call and start two SLA clocks. The first person to acknowledge becomes Lead.
            </p>
            <div className="btn-row">
              <a className="btn btn--primary" href="/signup">
                Create a workspace
                <svg className="m-arrow" viewBox="0 0 12 12" aria-hidden="true">
                  <path className="m-arrow__stem" d="M1 6h8" />
                  <path className="m-arrow__head" d="M4.5 2.5 8 6l-3.5 3.5" />
                </svg>
              </a>
              <a className="btn btn--secondary" href="#flow">
                See the incident flow
              </a>
            </div>
            <dl className="hero-facts">
              <div>
                <dt>
                  Severity
                </dt>
                <dd>
                  SEV1 to SEV4
                </dd>
              </div>
              <div>
                <dt>
                  Paged when
                </dt>
                <dd>
                  2 locations fail twice
                </dd>
              </div>
              <div>
                <dt>
                  Times
                </dt>
                <dd>
                  Shown in IST
                </dd>
              </div>
            </dl>
          </div>
          <div className="hero__media span-6 start-7">
            <div className="panel" data-fx="fluted-glass" data-fx-seed="14">
              <div className="stage">
                <div className="at-rise fit" style={{ "--fit-max": "440px", "--ax": "max(24px, 50% - 220px)", "--ay": "168px", "--ay-sm": "24px" } as CSSProperties} data-ui-loop="">
                  <div className="ui ui-notify" data-ui="notify">
                    <ul className="ui-notify__list" data-notify-list="">
                      <li className="ui-note">
                        <span className="ui-agent ui-note__av" aria-hidden="true" />
                        <span className="ui-note__text">
                          <span className="ui-note__title">
                            Settle paged you
                            <time>
                              now
                            </time>
                          </span>
                          <span className="ui-note__body">
                            INC-1043 · SEV1 · UPI payment authorisations failing
                          </span>
                        </span>
                        <span className="ui-btn ui-btn--sm ui-note__btn">
                          Acknowledge
                        </span>
                      </li>
                      <li className="ui-note">
                        <span className="ui-avatar ui-note__av" data-tone="3">
                          RS
                        </span>
                        <span className="ui-note__text">
                          <span className="ui-note__title">
                            Rohan Shah is Lead on INC-1041
                            <time>
                              4m
                            </time>
                          </span>
                          <span className="ui-note__body">
                            Response target met in 5 minutes
                          </span>
                        </span>
                      </li>
                      <li className="ui-note">
                        <span className="ui-note__ico">
                          <svg className="ui-icon" viewBox="0 0 16 16">
                            <path d="m3.5 8.4 2.8 2.8 6.2-6.4" />
                          </svg>
                        </span>
                        <span className="ui-note__text">
                          <span className="ui-note__title">
                            INC-1039 resolved
                            <time>
                              12m
                            </time>
                          </span>
                          <span className="ui-note__body">
                            Search indexing lag, inside its 24h target
                          </span>
                        </span>
                      </li>
                    </ul>
                  </div>
                </div>
              </div>
            </div>
            <div className="ly-float">
              <div className="ly-float__row">
                <span className="ly-live">
                  Response clock
                </span>
                <span>
                  3m left
                </span>
              </div>
              <div className="ly-float__title">
                INC-1043, waiting for acknowledgement
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
