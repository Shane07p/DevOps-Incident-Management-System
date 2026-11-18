import { Moon, Sun } from 'lucide-react'
import { useTheme } from '@/components/theme/ThemeProvider'
import { cn } from '@/lib/utils'

/** The square, borderless button of the top bar. */
export const iconButtonClass =
  'relative grid size-[30px] shrink-0 place-items-center rounded-md text-muted-foreground transition-colors outline-none hover:bg-accent hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring active:translate-y-px'

/** Switches between light and dark; the choice is remembered in this browser (ThemeProvider). */
export function ThemeToggle({ className }: { className?: string }) {
  const { resolvedTheme, setTheme } = useTheme()
  const dark = resolvedTheme === 'dark'
  return (
    <button
      type="button"
      onClick={() => setTheme(dark ? 'light' : 'dark')}
      aria-label={dark ? 'Switch to light theme' : 'Switch to dark theme'}
      title={dark ? 'Switch to light theme' : 'Switch to dark theme'}
      className={cn(iconButtonClass, className)}
    >
      {dark ? <Sun className="size-[17px]" aria-hidden="true" /> : <Moon className="size-[17px]" aria-hidden="true" />}
    </button>
  )
}
