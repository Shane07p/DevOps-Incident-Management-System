// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function LandingFooter() {
  return (
    <footer className="footer closing footer--dark ground-inverse" id="contact" aria-labelledby="close-h">
      <div className="wrap">
        <div className="closing__cta close--type">
          <div className="section-head">
            <h2 id="close-h">
              Create a workspace for your team
            </h2>
            <p>
              Sign-up creates your organisation. Add services, on-call schedules and your monitoring tools next.
            </p>
          </div>
          <a className="btn btn--primary btn--xl btn--wide" href="/signup">
            Create a workspace
            <svg className="m-arrow" viewBox="0 0 12 12" aria-hidden="true">
              <path className="m-arrow__stem" d="M1 6h8" />
              <path className="m-arrow__head" d="M4.5 2.5 8 6l-3.5 3.5" />
            </svg>
          </a>
        </div>
        <div className="closing__links">
          <div className="grid">
            <div className="span-4 stack-4">
              <a className="nav__logo" href="#top" aria-label="Settle home">
                <span className="wordmark">
                  settle
                  <svg viewBox="0 0 60 6" aria-hidden="true">
                    <path d="M1.5 3 Q9 0.5 16.5 3 T31.5 3 T46.5 3 T58.5 3" />
                  </svg>
                </span>
              </a>
              <p className="footer__lede">
                Incident response and monitoring for software teams.
              </p>
            </div>
            <div className="span-2 footer__col">
              <h3>
                Product
              </h3>
              <ul>
                <li>
                  <a href="#product">
                    Incident board
                  </a>
                </li>
                <li>
                  <a href="#flow">
                    Workflow
                  </a>
                </li>
              </ul>
            </div>
            <div className="span-2 footer__col">
              <h3>
                Monitoring
              </h3>
              <ul>
                <li>
                  <a href="#monitoring">
                    Uptime checks
                  </a>
                </li>
                <li>
                  <a href="#tools">
                    Integrations
                  </a>
                </li>
              </ul>
            </div>
            <div className="span-2 footer__col">
              <h3>
                Help
              </h3>
              <ul>
                <li>
                  <a href="#questions">
                    Questions
                  </a>
                </li>
                <li>
                  <a href="#settled">
                    The record
                  </a>
                </li>
              </ul>
            </div>
            <div className="span-2 footer__col">
              <h3>
                Account
              </h3>
              <ul>
                <li>
                  <a href="/login">
                    Sign in
                  </a>
                </li>
                <li>
                  <a href="/signup">
                    Create a workspace
                  </a>
                </li>
              </ul>
            </div>
          </div>
          <div className="footer__legal">
            <span>
              © 2026 Settle
            </span>
            <div className="socials" />
            <span>
              Product names and logos are trademarks of their owners
            </span>
          </div>
        </div>
      </div>
    </footer>
  )
}
