import type { ComponentProps, ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * A card section with an optional heading row (title, then an action such as a link). The heading's h2 names
 * the section for screen readers. Content sets its own padding, so a table can run edge to edge.
 */
export function Panel({
  title,
  action,
  children,
  className,
  ...props
}: { title?: ReactNode; action?: ReactNode; children: ReactNode } & Omit<ComponentProps<'section'>, 'title'>) {
  return (
    <section
      className={cn('min-w-0 overflow-hidden rounded-lg border bg-card text-card-foreground shadow-elev-1', className)}
      {...props}
    >
      {title && (
        <div className="flex items-center justify-between gap-3 px-[19px] py-[18px]">
          <h2 className="flex items-center gap-2 text-[13px] font-medium tracking-[-0.01em]">{title}</h2>
          {action}
        </div>
      )}
      {children}
    </section>
  )
}

/** The footer row of a panel: a hairline above, small muted text, links on the right. */
export function PanelFooter({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-wrap items-center justify-between gap-3 border-t px-[19px] py-3.5 text-[12px] text-muted-foreground', className)}>
      {children}
    </div>
  )
}
