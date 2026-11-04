import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlert, Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { ApiRequestError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AuthLayout } from '@/components/app/AuthLayout'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { showRequestErrors } from '@/lib/forms'

// The server checks the same rules (SignupRequest); these only save a round trip.
const signupSchema = z.object({
  email: z.email({ error: 'Enter a valid email' }),
  password: z.string().min(8, { error: 'At least 8 characters' }).max(72, { error: 'At most 72 characters' }),
  displayName: z.string().trim().min(1, { error: 'Enter your name' }).max(100, { error: 'At most 100 characters' }),
  orgName: z.string().trim().min(1, { error: 'Enter a name for your org' }).max(100, { error: 'At most 100 characters' }),
})

type SignupValues = z.infer<typeof signupSchema>

const EMPTY: SignupValues = { email: '', password: '', displayName: '', orgName: '' }

/** Anyone can create an org and becomes its Admin (D48). */
export function SignupPage() {
  const { status, signup } = useAuth()
  const navigate = useNavigate()
  const [showPassword, setShowPassword] = useState(false)
  const form = useForm<SignupValues>({ resolver: zodResolver(signupSchema), defaultValues: EMPTY })

  if (status === 'authenticated') return <Navigate to="/home" replace />

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      await signup(values)
      navigate('/home', { replace: true })
    } catch (error) {
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

  const fieldClass = 'h-[46px] rounded-md bg-card px-3.5 text-[15px] md:text-[15px]'

  return (
    <AuthLayout note="Your org gets its own services, incidents, and alerts. Add your team from Members once you're in.">
      <Form {...form}>
        <form onSubmit={onSubmit} noValidate className="flex w-full max-w-[380px] flex-col gap-5">
          <div className="flex flex-col gap-2.5">
            <h1 className="text-[64px] leading-[0.9] font-[750] tracking-[-0.01em]" style={{ fontStretch: '62%' }}>
              Create an org
            </h1>
            <p className="text-[15px] text-muted-foreground">You become its Admin and can add your team after.</p>
          </div>

          {form.formState.errors.root && (
            <div role="alert" className="flex items-center gap-2.5 rounded-md bg-sev-1-bg px-3.5 py-3 text-sm font-semibold text-sev-1-fg">
              <CircleAlert className="size-4 shrink-0" aria-hidden="true" />
              {form.formState.errors.root.message}
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
                      autoComplete="new-password"
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
                <FormDescription>8 to 72 characters.</FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="displayName"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="text-sm font-semibold">Your name</FormLabel>
                <FormControl>
                  <Input autoComplete="name" className={fieldClass} {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="orgName"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="text-sm font-semibold">Org name</FormLabel>
                <FormControl>
                  <Input autoComplete="organization" className={fieldClass} {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <Button type="submit" disabled={form.formState.isSubmitting} className="h-12 rounded-md text-[15px] font-bold">
            {form.formState.isSubmitting ? 'Creating…' : 'Create org'}
          </Button>

          <p className="text-center text-[13.5px] text-muted-foreground">
            Already have an account?{' '}
            <Link to="/login" className="font-semibold text-foreground">
              Sign in
            </Link>
          </p>
        </form>
      </Form>
    </AuthLayout>
  )
}
