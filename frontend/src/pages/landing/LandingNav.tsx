// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function LandingNav() {
  return (
    <header className="nav nav--centre">
      <div className="wrap nav__bar">
        <a className="skip-link" href="#top">
          Skip to content
        </a>
        <a className="nav__logo" href="#top" aria-label="Settle home">
          <span className="wordmark">
            settle
            <svg viewBox="0 0 60 6" aria-hidden="true">
              <path d="M1.5 3 Q9 0.5 16.5 3 T31.5 3 T46.5 3 T58.5 3" />
            </svg>
          </span>
        </a>
        <nav className="nav__links" aria-label="Main">
          <div className="nav__item">
            <a className="nav__link" href="#product">
              {"Product "}
              <svg className="chev" viewBox="0 0 12 12" aria-hidden="true">
                <path d="M3 4.5l3 3 3-3" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </a>
            <div className="nav__menu">
              <div className="wrap grid">
                <div className="menu-col span-3">
                  <p className="t-label">
                    Respond
                  </p>
                  <a href="#product">
                    Incident board
                    <span>
                      Most urgent incident first
                    </span>
                  </a>
                  <a href="#flow">
                    Acknowledge and resolve
                    <span>
                      From page to postmortem
                    </span>
                  </a>
                </div>
                <div className="menu-col span-3">
                  <p className="t-label">
                    Monitor
                  </p>
                  <a href="#monitoring">
                    Uptime checks
                    <span>
                      From several locations at once
                    </span>
                  </a>
                  <a href="#tools">
                    Alert intake
                    <span>
                      One webhook for every tool
                    </span>
                  </a>
                </div>
              </div>
            </div>
          </div>
          <div className="nav__item">
            <a className="nav__link" href="#flow">
              Workflow
            </a>
          </div>
          <div className="nav__item">
            <a className="nav__link" href="#monitoring">
              Monitoring
            </a>
          </div>
          <div className="nav__item">
            <a className="nav__link" href="#tools">
              Integrations
            </a>
          </div>
          <div className="nav__item">
            <a className="nav__link" href="#questions">
              Questions
            </a>
          </div>
        </nav>
        <div className="nav__actions">
          <a className="nav__link" href="/login">
            Sign in
          </a>
          <a className="btn btn--primary" href="/signup">
            Create a workspace
          </a>
        </div>
        <button className="nav__toggle" type="button" aria-expanded="false" aria-label="Menu">
          <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
            <path d="M2 5h14M2 13h14" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        </button>
      </div>
    </header>
  )
}
