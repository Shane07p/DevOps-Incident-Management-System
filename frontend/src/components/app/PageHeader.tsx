import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/** The top of every app page: an optional eyebrow, the title, one line of context, and the page's actions. */
export function PageHeader({
  title,
  eyebrow,
  description,
  actions,
  className,
}: {
  title: ReactNode
  eyebrow?: ReactNode
  description?: ReactNode
  actions?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('mb-[27px] flex flex-wrap items-end justify-between gap-4', className)}>
      <div className="flex min-w-0 flex-col gap-2">
        {eyebrow && <div className="text-[12px] font-medium text-muted-foreground">{eyebrow}</div>}
        <h1 className="text-[28px] leading-[1.1] font-medium tracking-[-0.035em] text-balance nav:text-[34px]">{title}</h1>
        {description && <p className="text-[13px] leading-[1.7] text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2.5">{actions}</div>}
    </div>
  )
}
