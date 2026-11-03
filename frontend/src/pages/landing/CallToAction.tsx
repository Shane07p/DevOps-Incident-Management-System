import { Link } from 'react-router-dom'
import { useIstTime } from '@/components/app/IstClock'

export function CallToAction() {
  const { minutes, seconds, hhmmss } = useIstTime()
  const circumference = 590.6
  const filled = ((minutes * 60 + seconds) / 3600) * circumference

  return (
    <section className="dark relative isolate overflow-hidden bg-deep text-foreground">
      <div
        aria-hidden="true"
        className="absolute inset-0 -z-10 [background-image:radial-gradient(oklch(1_0_0/0.08)_1px,transparent_1.2px)] [background-size:24px_24px] [mask-image:radial-gradient(ellipse_60%_70%_at_80%_50%,black,transparent_70%)]"
      />
      <div className="mx-auto flex max-w-(--page-max) flex-wrap items-center justify-between gap-14 px-4 py-[120px] sm:px-8">
        <div className="flex min-w-0 flex-[1_1_520px] flex-col items-start gap-7">
          <h2 className="max-w-[900px] text-[clamp(56px,7.6vw,116px)] leading-[0.86] font-[750] tracking-[-0.015em]" style={{ fontStretch: '62%' }}>
            When the pager goes off, you'll know what to do next.
          </h2>
          <div className="flex flex-wrap items-center gap-3.5">
            <Link
              to="/login"
              className="inline-flex h-[50px] items-center rounded-full bg-primary px-7 text-base font-bold text-primary-foreground shadow-[0_10px_30px_-8px_color-mix(in_oklch,var(--glow)_60%,transparent)] hover:no-underline"
            >
              Log in
            </Link>
            <span className="text-muted-foreground">
              New team? <Link to="/signup">Create an org</Link>
            </span>
          </div>
        </div>
        <div aria-hidden="true" className="relative size-60">
          <svg width="240" height="240" viewBox="0 0 240 240">
            <circle cx="120" cy="120" r="112" fill="none" stroke="oklch(1 0 0 / 0.14)" strokeWidth="5" strokeDasharray="0.9 10.83" />
            <circle cx="120" cy="120" r="94" fill="none" stroke="oklch(1 0 0 / 0.07)" strokeWidth="12" />
            <circle
              cx="120"
              cy="120"
              r="94"
              fill="none"
              stroke="var(--primary)"
              strokeWidth="12"
              strokeDasharray={`${filled.toFixed(1)} ${circumference}`}
              transform="rotate(-90 120 120)"
              className="transition-[stroke-dasharray] duration-1000 ease-linear"
            />
            <line x1="120" y1="120" x2="120" y2="22" stroke="var(--glow)" strokeWidth="1.5" strokeLinecap="round" transform={`rotate(${seconds * 6} 120 120)`} />
            <circle cx="120" cy="120" r="4" fill="var(--glow)" />
          </svg>
          <span className="absolute right-0 -bottom-[30px] left-0 text-center font-mono text-[12.5px] font-medium text-muted-foreground tnum">{hhmmss} IST</span>
        </div>
      </div>
    </section>
  )
}
