// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function QuestionsSection() {
  return (
    <section className="section" id="questions" aria-labelledby="questions-h">
      <div className="wrap grid grid--top">
        <div className="span-6">
          <div className="section-head">
            <h2 id="questions-h">
              Questions on-call teams ask
            </h2>
          </div>
          <div className="faq">
            <div className="faq__item">
              <h3>
                Who becomes Lead on an incident?
              </h3>
              <p>
                Whoever acknowledges first. Escalation notifies the next person if nobody does, but never reassigns.
              </p>
            </div>
            <div className="faq__item">
              <h3>
                Does the AI change incidents?
              </h3>
              <p>
                No. It suggests a cause and shows its evidence. Status, clocks and escalation follow Settle's own rules.
              </p>
            </div>
            <div className="faq__item">
              <h3>
                What happens when an incident misses its target?
              </h3>
              <p>
                The clock turns red and counts up past the target, which stays as it was when the incident opened.
              </p>
            </div>
            <div className="faq__item">
              <h3>
                Who can change an incident?
              </h3>
              <p>
                Admins configure, Engineers respond and Viewers read. The server checks every request.
              </p>
            </div>
          </div>
        </div>
        <div className="panel shot shot--faq span-6 start-7" role="img" aria-label="An incident's SLA targets in Settle: response met in 5 minutes; resolution breached, 30 minutes over its 8 hour target.">
          <div className="ui app-card sla-card sla-card--large">
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
                5m
              </code>
              <i className="sc-bar">
                <u style={{ width: "3%" }} />
              </i>
              <small>
                Acknowledged in 5m · target 4h
              </small>
            </div>
            <div className="sc-block sc-block--breach">
              <span>
                {"Resolution "}
                <em className="sc-tag sc-tag--breach">
                  Breached
                </em>
              </span>
              <code>
                +30m
              </code>
              <i className="sc-bar">
                <u style={{ width: "100%" }} />
              </i>
              <small>
                over the 8h target
              </small>
            </div>
            <div className="sc-foot">
              Targets fixed when the incident opened
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
