// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function SettledSection() {
  return (
    <section className="section" id="settled" aria-labelledby="settled-h">
      <div className="wrap">
        <div data-moment="liquid-type" className="section-head" style={{ maxWidth: "none" }}>
          <h2 id="settled-h" className="mo-lt__type">
            Every incident, settled
          </h2>
          <p>
            SLA targets are copied when an incident opens and never change, so the record shows what was promised and what happened.
          </p>
        </div>
        <ul className="rows">
          <li>
            <span className="rows__name">
              Targets
              <small>
                Copied from the service when the incident opens
              </small>
            </span>
            <span className="rows__value">
              Fixed
            </span>
          </li>
          <li>
            <span className="rows__name">
              Timeline
              <small>
                Alerts, status changes, notes, deploys and AI findings
              </small>
            </span>
            <span className="rows__value">
              Append-only
            </span>
          </li>
          <li>
            <span className="rows__name">
              SEV1 and SEV2
              <small>
                A reviewed postmortem before the incident closes
              </small>
            </span>
            <span className="rows__value">
              Required
            </span>
          </li>
          <li>
            <span className="rows__name">
              Every change
              <small>
                Attributed to a person, with the time in IST
              </small>
            </span>
            <span className="rows__value">
              Signed
            </span>
          </li>
          <li>
            <span className="rows__name">
              Corrections
              <small>
                Added as amendments linked to the entry they correct
              </small>
            </span>
            <span className="rows__value">
              Linked
            </span>
          </li>
        </ul>
      </div>
    </section>
  )
}
