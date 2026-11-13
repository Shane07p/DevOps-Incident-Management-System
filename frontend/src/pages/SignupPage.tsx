import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { showRequestErrors } from '@/lib/forms'
import { AuthField } from './auth/AuthField'
import { AuthLayout } from './auth/AuthLayout'
import { settleDelay } from './auth/identity'

// The server checks the same rules (SignupRequest); these only save a round trip.
const signupSchema = z.object({
  orgName: z.string().trim().min(1, { error: 'Write your organisation’s name.' }).max(100, { error: 'Use at most 100 characters.' }),
  displayName: z.string().trim().min(1, { error: 'Write your name as your team knows it.' }).max(100, { error: 'Use at most 100 characters.' }),
  email: z.email({ error: 'Write your work email, like name@company.com.' }),
  password: z.string().min(8, { error: 'Use 8 or more characters.' }).max(72, { error: 'Use at most 72 characters.' }),
})

type SignupValues = z.infer<typeof signupSchema>

const EMPTY: SignupValues = { orgName: '', displayName: '', email: '', password: '' }

/** Anyone can create a workspace (an org) and becomes its Admin (D48). */
export function SignupPage() {
  const { status, signup } = useAuth()
  const navigate = useNavigate()
  const [phase, setPhase] = useState<'idle' | 'creating' | 'entering'>('idle')
  const form = useForm<SignupValues>({ resolver: zodResolver(signupSchema), defaultValues: EMPTY, mode: 'onBlur', reValidateMode: 'onBlur' })
  const [orgName, displayName] = useWatch({ control: form.control, name: ['orgName', 'displayName'] })

  useEffect(() => {
    document.title = 'Create a workspace · Settle'
  }, [])

  if (status === 'authenticated' && phase === 'idle') return <Navigate to="/home" replace />

  const onSubmit = form.handleSubmit(async (values) => {
    if (phase !== 'idle') return
    setPhase('creating')
    try {
      await signup(values)
      setPhase('entering')
      window.setTimeout(() => navigate('/home', { replace: true }), settleDelay())
    } catch (error) {
      setPhase('idle')
      if (error instanceof ApiRequestError && error.status === 409) {
        // The only conflict is an email that already has an account.
        form.setError('email', { message: error.message })
      } else if (error instanceof ApiRequestError && error.status !== 429 && error.status < 500) {
        showRequestErrors(form, error, Object.keys(EMPTY))
      } else {
        form.setError('root', {
          message: error instanceof ApiRequestError && error.status === 429 ? error.message : 'Settle could not be reached. Try again in a moment.',
        })
      }
    }
  })

  return (
    <AuthLayout wall={{ org: orgName.trim(), person: displayName.trim().slice(0, 24), entering: phase === 'entering' }}>
      <h2>Create a workspace</h2>
      <p>Sign-up creates your organisation, and you become its Admin. Add services, on-call schedules and monitoring tools next.</p>
      <form className="auth-form" noValidate onSubmit={onSubmit}>
        <AuthField form={form} name="orgName" label="Organisation" type="text" autoComplete="organization" />
        <AuthField form={form} name="displayName" label="Your name" type="text" autoComplete="name" />
        <AuthField form={form} name="email" label="Work email" type="email" autoComplete="email" />
        <AuthField form={form} name="password" label="Password" type="password" autoComplete="new-password" />
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
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </AuthLayout>
  )
}
