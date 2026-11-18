import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { fetchOAuthSignupDetails } from '@/api/auth'
import { ApiRequestError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { showRequestErrors } from '@/lib/forms'
import { AuthField } from './AuthField'
import { AuthLayout } from './AuthLayout'
import { settleDelay } from './identity'

// The server checks the same rules (OAuthSignupRequest); these only save a round trip.
const finishSchema = z.object({
  orgName: z.string().trim().min(1, { error: 'Write your organisation’s name.' }).max(100, { error: 'Use at most 100 characters.' }),
  displayName: z.string().trim().min(1, { error: 'Write your name as your team knows it.' }).max(100, { error: 'Use at most 100 characters.' }),
})

type FinishValues = z.infer<typeof finishSchema>

const PROVIDER_NAME: Record<string, string> = { google: 'Google', github: 'GitHub' }

/** The code from the URL fragment, read once; it then leaves the address bar so it never stays in history. */
function useFragmentCode() {
  const [code] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('code'))
  useEffect(() => {
    if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname)
  }, [])
  return code
}

/**
 * The last step of signing up with Google or GitHub (D50): the provider vouched for the email, so the person only
 * names their workspace (an org) and the name their team will see.
 */
export function OAuthSignupPage() {
  const { finishOAuthSignup } = useAuth()
  const navigate = useNavigate()
  const code = useFragmentCode()
  const [phase, setPhase] = useState<'idle' | 'creating' | 'entering'>('idle')
  const details = useQuery({
    queryKey: ['oauth-signup', code],
    queryFn: () => fetchOAuthSignupDetails(code ?? ''),
    enabled: code !== null,
    retry: false,
    staleTime: Infinity,
  })
  const form = useForm<FinishValues>({
    resolver: zodResolver(finishSchema),
    defaultValues: { orgName: '', displayName: '' },
    mode: 'onBlur',
    reValidateMode: 'onBlur',
  })
  const [orgName, displayName] = useWatch({ control: form.control, name: ['orgName', 'displayName'] })

  useEffect(() => {
    document.title = 'Create a workspace · Settle'
  }, [])

  // Start the name field with the provider's name; the person can change it.
  const providerName = details.data?.displayName
  useEffect(() => {
    if (providerName && !form.getValues('displayName')) form.setValue('displayName', providerName)
  }, [providerName, form])

  const onSubmit = form.handleSubmit(async (values) => {
    if (phase !== 'idle' || !code) return
    setPhase('creating')
    try {
      await finishOAuthSignup(code, values)
      setPhase('entering')
      window.setTimeout(() => navigate('/home', { replace: true }), settleDelay())
    } catch (error) {
      setPhase('idle')
      if (error instanceof ApiRequestError && (error.status === 401 || error.status === 409 || error.status === 429)) {
        form.setError('root', { message: error.message })
      } else if (error instanceof ApiRequestError && error.status < 500) {
        showRequestErrors(form, error, ['orgName', 'displayName'])
      } else {
        form.setError('root', { message: 'Settle could not be reached. Try again in a moment.' })
      }
    }
  })

  const wall = { org: orgName.trim(), person: displayName.trim().slice(0, 24), entering: phase === 'entering' }

  if (code === null || details.isError) {
    return (
      <AuthLayout wall={wall}>
        <h2>This sign-up link has expired</h2>
        <p role="alert">
          {details.error instanceof ApiRequestError && details.error.status === 401
            ? details.error.message
            : code === null
              ? 'The link is incomplete. Start again from the sign-up page.'
              : 'Settle could not be reached. Try again in a moment.'}
        </p>
        <Link className="button primary auth-submit" to="/signup">
          Back to sign up
        </Link>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout wall={wall}>
      <h2>Create a workspace</h2>
      <p>
        {details.data
          ? `${PROVIDER_NAME[details.data.provider] ?? 'Your provider'} confirmed ${details.data.email}. Name your organisation, and you become its Admin.`
          : 'Checking your sign-in…'}
      </p>
      <form className="auth-form" noValidate onSubmit={onSubmit}>
        <AuthField form={form} name="orgName" label="Organisation" type="text" autoComplete="organization" />
        <AuthField form={form} name="displayName" label="Your name" type="text" autoComplete="name" />
        {form.formState.errors.root && (
          <p className="auth-error" role="alert">
            {form.formState.errors.root.message}
          </p>
        )}
        <button className="button primary auth-submit" type="submit" aria-busy={phase !== 'idle' || undefined}>
          {phase === 'idle' ? 'Create a workspace' : 'Creating your workspace…'}
        </button>
      </form>
      <p className="auth-switch">
        Not you? <Link to="/login">Sign in another way</Link>
      </p>
    </AuthLayout>
  )
}
