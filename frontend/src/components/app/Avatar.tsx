import { cn } from '@/lib/utils'

/** "Asha Patel" → "AP"; one word gives one letter. */
function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

/** A person's initials in a circle; the full name is the accessible name, so nothing reads "A P". */
export function Avatar({ name, size = 'md', className }: { name: string; size?: 'sm' | 'md'; className?: string }) {
  return (
    <span
      role="img"
      aria-label={name}
      title={name}
      className={cn(
        'inline-grid shrink-0 place-items-center rounded-full border bg-accent font-[550] text-foreground select-none',
        size === 'sm' ? 'size-[26px] text-[11px]' : 'size-[34px] text-[12px]',
        className,
      )}
    >
      <span aria-hidden="true">{initials(name)}</span>
    </span>
  )
}
