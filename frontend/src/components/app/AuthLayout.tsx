import { ArrowLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { IstClock } from '@/components/app/IstClock'
import { ThemeToggle } from '@/components/shell/ThemeToggle'
import { Wordmark } from '@/components/shell/Wordmark'

/** The two-column frame of the log-in and sign-up pages: the form on the left, the clock panel on the right. */
export function AuthLayout({ children, note }: { children: ReactNode; note: string }) {
  return (
    <div className="flex min-h-screen flex-wrap bg-background text-[14px] text-foreground">
      <div className="flex min-h-screen min-w-0 flex-[1_1_460px] flex-col gap-6 px-6 py-7 sm:px-10">
        <div className="flex items-center justify-between gap-4">
          <Link to="/" aria-label="Settle website" className="text-foreground hover:no-underline">
            <Wordmark size={28} />
          </Link>
          <div className="flex items-center gap-2">
            <Link
              to="/"
              className="inline-flex h-9 items-center gap-1.5 rounded-full px-3 text-[13px] font-semibold text-muted-foreground hover:bg-muted hover:no-underline"
            >
              <ArrowLeft className="size-3.5" aria-hidden="true" />
              Back to website
            </Link>
            <ThemeToggle />
          </div>
        </div>

        <div className="grid flex-1 place-items-center">{children}</div>

        <div className="text-[12.5px] text-muted-foreground">IT314 course project, Group 2</div>
      </div>

      <div className="dark relative isolate hidden min-h-screen min-w-0 flex-[1_1_460px] flex-col items-center justify-center gap-9 overflow-hidden bg-deep p-12 text-foreground lg:flex">
        <div
          aria-hidden="true"
          className="absolute inset-0 -z-10 [background-image:radial-gradient(oklch(1_0_0/0.09)_1px,transparent_1.2px)] [background-size:24px_24px] [mask-image:radial-gradient(ellipse_60%_60%_at_50%_45%,black_30%,transparent_75%)]"
        />
        <IstClock />
        <div className="flex max-w-[460px] flex-col items-center gap-3.5 text-center">
          <h2 className="text-[52px] leading-[0.9] font-[750]" style={{ fontStretch: '62%' }}>
            Every incident runs on a clock.
          </h2>
          <p className="text-[15px] text-pretty text-muted-foreground">{note}</p>
        </div>
      </div>
    </div>
  )
}
