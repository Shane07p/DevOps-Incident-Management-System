import { useQueryClient } from '@tanstack/react-query'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { fetchMe, login as loginRequest, logout as logoutRequest, signup as signupRequest, type SignupValues } from '@/api/auth'
import { setUnauthorizedHandler } from '@/api/client'
import type { LoginResponse, Org, User } from '@/api/schemas'
import { clearToken, readToken, writeToken } from '@/api/token'

type Status = 'loading' | 'authenticated' | 'anonymous'

interface AuthState {
  status: Status
  user: User | null
  org: Org | null
  login: (email: string, password: string) => Promise<User>
  signup: (values: SignupValues) => Promise<User>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [org, setOrg] = useState<Org | null>(null)
  const [status, setStatus] = useState<Status>(() => (readToken() ? 'loading' : 'anonymous'))
  const queryClient = useQueryClient()

  // Everything cached belongs to the session that loaded it: another person's incidents or
  // notifications must not show, even for a moment, after a sign-out or a sign-in (FR-01.5). Clearing
  // also drops queries still in flight, so a late answer for the old session lands nowhere.
  const forgetSession = useCallback(() => {
    void queryClient.cancelQueries()
    queryClient.clear()
  }, [queryClient])

  const signOutLocally = useCallback(() => {
    clearToken()
    forgetSession()
    setUser(null)
    setOrg(null)
    setStatus('anonymous')
  }, [forgetSession])

  // A saved token may have expired or been revoked; /me tells us before the app trusts it. The login
  // form shows meanwhile, so if someone signs in before /me answers, that answer is about a token
  // that is no longer ours, and is ignored.
  useEffect(() => {
    const token = readToken()
    if (!token) return
    fetchMe()
      .then((me) => {
        if (readToken() !== token) return
        setUser(me.user)
        setOrg(me.org)
        setStatus('authenticated')
      })
      .catch(() => {
        if (readToken() === token) signOutLocally()
      })
  }, [signOutLocally])

  useEffect(() => {
    setUnauthorizedHandler(signOutLocally)
    return () => setUnauthorizedHandler(null)
  }, [signOutLocally])

  const signIn = useCallback((response: LoginResponse) => {
    writeToken(response.token)
    forgetSession()
    setUser(response.user)
    setOrg(response.org)
    setStatus('authenticated')
    return response.user
  }, [forgetSession])

  const login = useCallback(async (email: string, password: string) => signIn(await loginRequest(email, password)), [signIn])

  const signup = useCallback(async (values: SignupValues) => signIn(await signupRequest(values)), [signIn])

  const logout = useCallback(async () => {
    // The server bumps the token version so the token stops working everywhere; sign out
    // locally even if that call fails.
    await logoutRequest().catch(() => undefined)
    signOutLocally()
  }, [signOutLocally])

  const value = useMemo(() => ({ status, user, org, login, signup, logout }), [status, user, org, login, signup, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
