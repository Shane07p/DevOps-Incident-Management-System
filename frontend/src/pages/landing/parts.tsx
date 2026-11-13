import { motion } from 'motion/react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/** One page width for every landing section, the same as the header's. */
export function Container({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('mx-auto w-full max-w-[var(--page-max)] px-5 sm:px-8 lg:px-10', className)}>{children}</div>
}

/** A section on one screen's rhythm, with the anchor the header and footer link to. */
export function Section({ id, className, children }: { id?: string; className?: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={id ? `${id}-heading` : undefined} className={cn('scroll-mt-20 py-24 sm:py-28', className)}>
      {children}
    </section>
  )
}

export function Heading({ id, className, children }: { id?: string; className?: string; children: ReactNode }) {
  return (
    <h2
      id={id ? `${id}-heading` : undefined}
      className={cn('text-[clamp(2.25rem,4vw+0.5rem,3.25rem)] leading-[1.05] font-medium tracking-[-0.035em] text-balance', className)}
    >
      {children}
    </h2>
  )
}

/**
 * The lit ground: a soft sand mesh behind each product picture. The product sits on it as a dark card,
 * so the picture reads the same in both themes.
 */
export function Ground({ className, children, label }: { className?: string; children: ReactNode; label?: string }) {
  return (
    <div
      role={label ? 'img' : undefined}
      aria-label={label}
      className={cn(
        'relative isolate grid place-items-center overflow-hidden rounded-xl bg-ground p-6 sm:p-10',
        '[background-image:radial-gradient(70%_90%_at_12%_0%,color-mix(in_oklab,var(--ground-surface)_92%,white),transparent_70%),radial-gradient(60%_80%_at_100%_100%,color-mix(in_oklab,var(--ground-accent)_34%,var(--ground)),transparent_72%),radial-gradient(50%_60%_at_85%_10%,color-mix(in_oklab,var(--ground-surface)_60%,transparent),transparent_70%)]',
        className,
      )}
    >
      {children}
    </div>
  )
}

/** A piece of Settle's own interface, drawn as it looks in the app. */
export function MockCard({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div
      className={cn(
        'rounded-xl border border-border bg-card text-left text-[13px] text-card-foreground shadow-[0_24px_60px_-24px_oklch(0.15_0.01_60/0.55)]',
        className,
      )}
    >
      {children}
    </div>
  )
}

/** Fades in and rises a little the first time it scrolls into view; reduced motion keeps only the fade. */
export function Reveal({ className, delay = 0, children }: { className?: string; delay?: number; children: ReactNode }) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 18 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '0px 0px -80px 0px' }}
      transition={{ duration: 0.5, delay, ease: [0.2, 0.8, 0.2, 1] }}
    >
      {children}
    </motion.div>
  )
}

/** Initials in a round chip, as on the incident board. */
export function Initials({ children, className }: { children: string; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn('grid size-6 shrink-0 place-items-center rounded-full bg-secondary text-[10px] font-semibold text-secondary-foreground', className)}
    >
      {children}
    </span>
  )
}
