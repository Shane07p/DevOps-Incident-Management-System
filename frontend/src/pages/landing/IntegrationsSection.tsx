// The redesign preview's markup for this part of the landing, styled and animated by the kit in
// public/landing (see LandingPage). Classes and data attributes are the kit's; keep them as they are.
export function IntegrationsSection() {
  return (
    <section className="section" id="tools" aria-labelledby="tools-h">
      <div className="wrap">
        <div className="section-head">
          <h2 id="tools-h">
            Works with Grafana, Prometheus and OpenTelemetry
          </h2>
          <p>
            Each tool posts to one webhook with its own token. Repeated events count once, and related alerts join the open incident by correlation key.
          </p>
        </div>
        <div className="panel">
          <div className="dg" data-diagram="">
            <script type="application/json" dangerouslySetInnerHTML={{ __html: "{\"layout\": \"fanin\", \"alt\": \"Grafana, Prometheus, OpenTelemetry, GitHub Actions and Settle's own checks send alerts to one webhook, which opens or updates one incident.\", \"sources\": [{\"label\": \"Grafana\", \"icon\": \"grafana\", \"sub\": \"Alert rules\"}, {\"label\": \"Prometheus\", \"icon\": \"prometheus\", \"sub\": \"Metrics\"}, {\"label\": \"OpenTelemetry\", \"icon\": \"opentelemetry\", \"sub\": \"Logs and traces\"}, {\"label\": \"GitHub Actions\", \"icon\": \"github-actions\", \"sub\": \"Deploys\"}, {\"label\": \"Uptime checks\", \"icon\": \"ui:activity\", \"sub\": \"Settle\"}, {\"label\": \"Heartbeats\", \"icon\": \"ui:heart-pulse\", \"sub\": \"Settle\"}], \"target\": {\"label\": \"Alert webhook\", \"icon\": \"ui:webhook\"}, \"then\": {\"label\": \"One incident\", \"icon\": \"ui:siren\", \"meta\": \"deduplicated\"}}" }} />
          </div>
        </div>
      </div>
    </section>
  )
}
