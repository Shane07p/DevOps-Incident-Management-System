import { cn } from '@/lib/utils'

/** "settle" in Host Grotesk over the orange wave. */
export function Wordmark({ size = 26, className }: { size?: number; className?: string }) {
  return (
    <span className={cn('inline-flex flex-col items-start leading-none', className)}>
      <span className="font-[650] tracking-[-0.045em]" style={{ fontSize: size }}>
        settle
      </span>
      <svg
        width={Math.round(size * 2.1)}
        height="6"
        viewBox="0 0 60 6"
        preserveAspectRatio="none"
        aria-hidden="true"
        className="mt-[3px]"
      >
        <path
          d="M1.5 3 Q9 0.5 16.5 3 T31.5 3 T46.5 3 T58.5 3"
          fill="none"
          stroke="var(--primary)"
          strokeWidth="2.2"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </span>
  )
}
