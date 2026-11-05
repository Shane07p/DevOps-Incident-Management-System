import { zodResolver } from '@hookform/resolvers/zod'
import { Building2, CircleAlert, Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AuthLayout } from '@/components/app/AuthLayout'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'

const loginSchema = z.object({
  email: z.email({ error: 'Enter your email' }),
  password: z.string().min(1, { error: 'Enter your password' }).max(72, { error: 'Passwords are at most 72 characters' }),
})

type LoginValues = z.infer<typeof loginSchema>

export function LoginPage() {
  const { status, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [showPassword, setShowPassword] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const form = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } })
  const from = (location.state as { from?: string } | null)?.from ?? '/home'

  if (status === 'authenticated') return <Navigate to={from} replace />

  const onSubmit = form.handleSubmit(async ({ email, password }) => {
    setFailure(null)
    try {
      await login(email, password)
      navigate(from, { replace: true })
    } catch (error) {
      setFailure(
        error instanceof ApiRequestError && error.status === 401
          ? 'Invalid email or password'
          : 'Settle could not be reached. Try again in a moment.',
      )
    }
  })

  const fieldClass = 'h-[46px] rounded-md bg-card px-3.5 text-[15px] md:text-[15px]'

  return (
    <AuthLayout note="After you log in you land on your work: incidents you're in, alerts routed to you, and your on-call shift.">
      <Form {...form}>
        <form onSubmit={onSubmit} noValidate className="flex w-full max-w-[380px] flex-col gap-5">
          <div className="flex flex-col gap-2.5">
            <h1 className="text-[64px] leading-[0.9] font-[750] tracking-[-0.01em]" style={{ fontStretch: '62%' }}>
              Log in
            </h1>
            <p className="text-[15px] text-muted-foreground">Sign in with your email and password.</p>
          </div>

          {failure && (
            <div role="alert" className="flex items-center gap-2.5 rounded-md bg-sev-1-bg px-3.5 py-3 text-sm font-semibold text-sev-1-fg">
              <CircleAlert className="size-4 shrink-0" aria-hidden="true" />
              {failure}
            </div>
          )}

          <FormField
            control={form.control}
            name="email"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="text-sm font-semibold">Email</FormLabel>
                <FormControl>
                  <Input type="email" autoComplete="email" className={fieldClass} {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="password"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="text-sm font-semibold">Password</FormLabel>
                <div className="relative flex items-center">
                  <FormControl>
                    <Input
                      type={showPassword ? 'text' : 'password'}
                      autoComplete="current-password"
                      className={`${fieldClass} pr-12`}
                      {...field}
                    />
                  </FormControl>
                  <button
                    type="button"
                    onClick={() => setShowPassword((shown) => !shown)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    className="absolute right-1.5 grid size-9 place-items-center rounded-md text-muted-foreground hover:bg-muted"
                  >
                    {showPassword ? <EyeOff className="size-4" aria-hidden="true" /> : <Eye className="size-4" aria-hidden="true" />}
                  </button>
                </div>
                <FormMessage />
              </FormItem>
            )}
          />

          <Button type="submit" disabled={form.formState.isSubmitting} className="h-12 rounded-md text-[15px] font-bold">
            {form.formState.isSubmitting ? 'Logging in…' : 'Log in'}
          </Button>

          <div className="flex items-start gap-2.5 rounded-md bg-muted px-4 py-3.5 text-[13.5px] text-muted-foreground">
            <Building2 className="mt-0.5 size-[15px] shrink-0" aria-hidden="true" />
            <span className="text-pretty">
              New to Settle?{' '}
              <Link to="/signup" className="font-semibold text-foreground">
                Create an org
              </Link>
              . Joining a team that already uses it? Ask one of its Admins to add you.
            </span>
          </div>
        </form>
      </Form>
    </AuthLayout>
  )
}
