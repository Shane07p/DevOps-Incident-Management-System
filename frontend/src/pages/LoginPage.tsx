import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AuthField } from './auth/AuthField'
import { AuthLayout } from './auth/AuthLayout'
import { nameFromEmail, orgFromEmail, settleDelay } from './auth/identity'

const loginSchema = z.object({
  email: z.email({ error: 'Write your work email, like name@company.com.' }),
  password: z.string().min(1, { error: 'Write your password.' }).max(72, { error: 'Passwords are at most 72 characters.' }),
})

type LoginValues = z.infer<typeof loginSchema>

export function LoginPage() {
  const { status, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // 'entering' holds the page while the wall turns, after a successful sign-in.
  const [phase, setPhase] = useState<'idle' | 'signing-in' | 'entering'>('idle')
  const [failure, setFailure] = useState<string | null>(null)
  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
    mode: 'onBlur',
    reValidateMode: 'onBlur',
  })
  const from = (location.state as { from?: string } | null)?.from ?? '/home'
  const email = useWatch({ control: form.control, name: 'email' })

  useEffect(() => {
    document.title = 'Sign in · Settle'
  }, [])

  // Someone already signed in goes straight on; a sign-in from this page waits for the wall first.
  if (status === 'authenticated' && phase === 'idle') return <Navigate to={from} replace />

  const onSubmit = form.handleSubmit(async ({ email, password }) => {
    if (phase !== 'idle') return
    setFailure(null)
    setPhase('signing-in')
    try {
      await login(email, password)
      setPhase('entering')
      window.setTimeout(() => navigate(from, { replace: true }), settleDelay())
    } catch (error) {
      setPhase('idle')
      setFailure(
        error instanceof ApiRequestError && error.status === 401
          ? 'Invalid email or password.'
          : error instanceof ApiRequestError && error.status === 429
            ? error.message
            : 'Settle could not be reached. Try again in a moment.',
      )
    }
  })

  return (
    <AuthLayout wall={{ org: orgFromEmail(email), person: nameFromEmail(email), entering: phase === 'entering' }}>
      <h2>Sign in to Settle</h2>
      <p>Use your work email and password.</p>
      <form className="auth-form" noValidate onSubmit={onSubmit}>
        <AuthField form={form} name="email" label="Work email" type="email" autoComplete="email" placeholder="asha.patel@settle.example" />
        <AuthField form={form} name="password" label="Password" type="password" autoComplete="current-password" />
        {failure && (
          <p className="auth-error" role="alert">
            {failure}
          </p>
        )}
        <button className="button primary auth-submit" type="submit" aria-busy={phase !== 'idle' || undefined}>
          {phase === 'idle' ? 'Sign in' : 'Signing in…'}
        </button>
      </form>
      <p className="auth-switch">
        New to Settle? <Link to="/signup">Create a workspace</Link>. Joining a team that already uses it? Ask one of its Admins
        to add you.
      </p>
    </AuthLayout>
  )
}
