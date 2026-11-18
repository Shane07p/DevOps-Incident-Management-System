import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiRequestError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AuthLayout } from './AuthLayout'

const INCOMPLETE = 'This sign-in link is incomplete. Sign in again.'

/**
 * Where a Google or GitHub sign-in comes back (D50): swaps the one-time code for a session, then opens Home.
 */
export function OAuthCallbackPage() {
  const { exchangeOAuth } = useAuth()
  const navigate = useNavigate()
  // The code from the URL fragment, read once; the effect below takes it out of the address bar.
  const [code] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('code'))
  const [failure, setFailure] = useState<string | null>(() => (code ? null : INCOMPLETE))
  // A code works once, so the exchange must not run twice (React runs effects twice in development).
  const started = useRef(false)

  useEffect(() => {
    document.title = 'Signing in · Settle'
    if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname)
    if (started.current || !code) return
    started.current = true
    exchangeOAuth(code)
      .then(() => navigate('/home', { replace: true }))
      .catch((error: unknown) =>
        setFailure(
          error instanceof ApiRequestError && error.status === 401
            ? error.message
            : 'Settle could not be reached. Try again in a moment.',
        ),
      )
  }, [code, exchangeOAuth, navigate])

  return (
    <AuthLayout wall={{ org: '', person: '', entering: failure === null }}>
      {failure ? (
        <>
          <h2>Sign-in didn't finish</h2>
          <p role="alert">{failure}</p>
          <Link className="button primary auth-submit" to="/login">
            Back to sign in
          </Link>
        </>
      ) : (
        <>
          <h2>Signing you in…</h2>
          <p>One moment.</p>
        </>
      )}
    </AuthLayout>
  )
}
