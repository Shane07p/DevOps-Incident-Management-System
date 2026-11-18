// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function MonitoringSection() {
  return (
    <section className="section" id="monitoring" aria-labelledby="monitoring-h">
      <div className="wrap">
        <div className="section-head">
          <h2 id="monitoring-h">
            {"Settle pages only when its checks agree. "}
            <span className="muted">
              One bad network path wakes nobody.
            </span>
          </h2>
        </div>
        <div className="grid">
          <article className="card card--row card--open span-6">
            <h3>
              Checks each URL from several locations
            </h3>
            <p>
              A service turns Down when two locations fail twice in a row, and Degraded when two are slow three times.
            </p>
            <div className="panel">
              <div className="dg" data-diagram="">
                <script type="application/json" dangerouslySetInnerHTML={{ __html: "{\"layout\": \"lanes\", \"curve\": \"curve\", \"alt\": \"One uptime check runs from three locations, A, B and C, and Settle compares the results before paging anyone.\", \"input\": {\"label\": \"URL\", \"icon\": \"ui:globe\", \"meta\": \"every 30 s\"}, \"core\": {\"label\": \"Uptime check\", \"icon\": \"ui:activity\"}, \"lanes\": [{\"label\": \"A\", \"icon\": \"ui:server\", \"meta\": \"200, 180 ms\", \"done\": true}, {\"label\": \"B\", \"icon\": \"ui:server\", \"meta\": \"200, 210 ms\", \"done\": true}, {\"label\": \"C\", \"icon\": \"ui:server\", \"meta\": \"timeout\"}], \"output\": {\"label\": \"Up\", \"icon\": \"ui:circle-check\", \"done\": true}}" }} />
              </div>
            </div>
          </article>
          <article className="card card--row card--open span-6">
            <h3>
              Tells customers what is down
            </h3>
            <p>
              The public status page shows each service's 90-day uptime and the updates Comms posts. Internal notes never appear there.
            </p>
            <div className="panel shot shot--tile" role="img" aria-label="Settle's public status page: some services degraded, with Checkout, Payments and Order processing degraded and Search operational.">
              <div className="ui app-card status-card">
                <div className="st-banner">
                  <i />
                  <b>
                    Some services are degraded
                  </b>
                  <small>
                    Updated 14:26 IST
                  </small>
                </div>
                <div className="st-row">
                  <span>
                    Checkout
                  </span>
                  <em className="st-warn">
                    Degraded
                  </em>
                  <i className="st-bars st-bars--warn" />
                </div>
                <div className="st-row">
                  <span>
                    Payments
                  </span>
                  <em className="st-warn">
                    Degraded
                  </em>
                  <i className="st-bars st-bars--warn" />
                </div>
                <div className="st-row">
                  <span>
                    Search
                  </span>
                  <em className="st-ok">
                    Operational
                  </em>
                  <i className="st-bars" />
                </div>
                <div className="st-row">
                  <span>
                    Order processing
                  </span>
                  <em className="st-warn">
                    Degraded
                  </em>
                  <i className="st-bars st-bars--warn" />
                </div>
              </div>
            </div>
          </article>
        </div>
      </div>
    </section>
  )
}
