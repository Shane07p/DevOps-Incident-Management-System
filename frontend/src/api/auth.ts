import { z } from 'zod'
import { apiFetch } from './client'
import { loginResponseSchema, meResponseSchema, noContentSchema } from './schemas'

export function login(email: string, password: string) {
  return apiFetch('/auth/login', loginResponseSchema, { method: 'POST', body: { email, password } })
}

export interface SignupValues {
  email: string
  password: string
  displayName: string
  orgName: string
}

/** Creates the account and a new org with the caller as its Admin, and signs them in. */
export function signup(values: SignupValues) {
  return apiFetch('/auth/signup', loginResponseSchema, { method: 'POST', body: values })
}

export function fetchMe() {
  return apiFetch('/auth/me', meResponseSchema)
}

export function logout() {
  return apiFetch('/auth/logout', noContentSchema, { method: 'POST' })
}

// Sign in with Google and GitHub (D50). The sign-in itself is a full-page visit to
// /api/auth/oauth2/authorization/{provider}; these calls redeem the one-time code it ends with.

export const oauthProvidersSchema = z.object({ providers: z.array(z.string()) })

/** The providers this server has set up; the app shows only their buttons. */
export function fetchOAuthProviders() {
  return apiFetch('/auth/oauth/providers', oauthProvidersSchema)
}

/** Swaps the code from /auth/callback for a session. The code works once. */
export function exchangeOAuthCode(code: string) {
  return apiFetch('/auth/oauth/exchange', loginResponseSchema, { method: 'POST', body: { code } })
}

export const oauthSignupDetailsSchema = z.object({ email: z.string(), displayName: z.string(), provider: z.string() })

/** The email and name behind a sign-up code, without using it up. */
export function fetchOAuthSignupDetails(code: string) {
  return apiFetch('/auth/oauth/signup/details', oauthSignupDetailsSchema, { method: 'POST', body: { code } })
}

/** Creates the org for a new Google or GitHub account, with them as its Admin, and signs them in. */
export function finishOAuthSignup(code: string, values: { orgName: string; displayName: string }) {
  return apiFetch('/auth/oauth/signup', loginResponseSchema, { method: 'POST', body: { code, ...values } })
}
