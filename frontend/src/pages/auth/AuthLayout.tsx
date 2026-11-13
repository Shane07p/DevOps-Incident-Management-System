import { ArrowUpRight } from 'lucide-react'
import type { ReactNode } from 'react'
import './auth.css'
import { ProductWall } from './ProductWall'

export type Wall = { org: string; person: string; entering: boolean }

function Wordmark() {
  return (
    <span className="wordmark">
      settle
      <svg viewBox="0 0 60 6" aria-hidden="true">
        <path d="M1.5 3 Q9 0.5 16.5 3 T31.5 3 T46.5 3 T58.5 3" />
      </svg>
    </span>
  )
}

/**
 * Sign-in and sign-up, from the redesign preview (#267): the landing's heading and a wall of Settle's own
 * screens on the left, the form on the right. The links to the landing are plain links, so it loads fresh.
 */
export function AuthLayout({ children, wall }: { children: ReactNode; wall: Wall }) {
  return (
    <div className="auth-page">
      <div className="login-v2">
        <section className="login-brand-side">
          <a className="brand" href="/" aria-label="Settle home">
            <Wordmark />
          </a>
          <div className="login-brand-copy">
            <h1>Incident management for on‑call teams</h1>
            <p>Alerts open one incident, the on-call engineer is paged, and both SLA clocks stay in view until it is resolved.</p>
          </div>
          <ProductWall {...wall} />
        </section>
        <section className="login-form-side">
          <div className="auth-form-wrap">{children}</div>
          <footer>
            <a href="/">
              Back to the Settle website
              <ArrowUpRight size={12} aria-hidden="true" />
            </a>
          </footer>
        </section>
      </div>
    </div>
  )
}
