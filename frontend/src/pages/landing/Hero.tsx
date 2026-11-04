import { RotateCcw } from 'lucide-react'
import { motion, useMotionTemplate, useMotionValue, useReducedMotion, useSpring } from 'motion/react'
import type { MouseEvent } from 'react'
import { Link } from 'react-router-dom'
import BlurText from '@/components/effects/BlurText'
import { Spotlight } from '@/components/effects/spotlight-new'
import { IncidentReplay } from './IncidentReplay'
import { useIncidentReplay } from './useIncidentReplay'

const ICE = 'hsla(205, 85%, 85%'

export function Hero() {
  const replay = useIncidentReplay()
  const reduceMotion = useReducedMotion()

  // A soft glow that drifts after the pointer.
  const x = useMotionValue(typeof window === 'undefined' ? 600 : window.innerWidth / 2)
  const y = useMotionValue(240)
  const glowX = useSpring(x, { stiffness: 60, damping: 20 })
  const glowY = useSpring(y, { stiffness: 60, damping: 20 })
  const glowTransform = useMotionTemplate`translate(${glowX}px, ${glowY}px)`

  const onMove = (event: MouseEvent<HTMLElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    x.set(event.clientX - box.left)
    y.set(event.clientY - box.top)
  }

  const replayFromButton = () => {
    document.getElementById('replay')?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth' })
    replay.play()
  }

  return (
    <section id="top" onMouseMove={onMove} className="dark relative isolate overflow-hidden bg-deep px-4 pt-[150px] pb-24 text-foreground sm:px-8">
      <div
        aria-hidden="true"
        className="absolute inset-0 -z-10 [background-image:radial-gradient(oklch(1_0_0/0.09)_1px,transparent_1.2px)] [background-size:24px_24px] [mask-image:radial-gradient(ellipse_70%_60%_at_50%_35%,black_30%,transparent_75%)]"
      />
      <motion.div
        aria-hidden="true"
        className="pointer-events-none absolute top-0 left-0 -z-10 -mt-[360px] -ml-[360px] size-[720px] rounded-full [background:radial-gradient(circle,color-mix(in_oklch,var(--glow)_28%,transparent),transparent_62%)]"
        style={{ transform: glowTransform }}
      />
      {!reduceMotion && (
        <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
          <Spotlight
            gradientFirst={`radial-gradient(68.54% 68.72% at 55.02% 31.46%, ${ICE}, .1) 0, ${ICE}, .03) 50%, transparent 80%)`}
            gradientSecond={`radial-gradient(50% 50% at 50% 50%, ${ICE}, .07) 0, ${ICE}, .02) 80%, transparent 100%)`}
            gradientThird={`radial-gradient(50% 50% at 50% 50%, ${ICE}, .05) 0, ${ICE}, .02) 80%, transparent 100%)`}
          />
        </div>
      )}

      <div className="mx-auto flex max-w-(--page-max) flex-col items-center gap-[26px] text-center">
        <a
          href="#replay"
          className="inline-flex h-8 items-center gap-2.5 rounded-full border border-white/12 bg-white/6 pr-1.5 pl-3.5 text-[13.5px] whitespace-nowrap text-foreground backdrop-blur-[10px] hover:no-underline"
        >
          Two clocks start the moment an incident opens
          <span className="h-[22px] rounded-full bg-primary px-[9px] text-xs leading-[22px] font-semibold text-primary-foreground">Watch</span>
        </a>

        <BlurText
          as="h1"
          text="Every incident runs on a clock."
          animateBy="words"
          direction="bottom"
          delay={90}
          blur={12}
          distance={18}
          className="max-w-[1100px] justify-center text-[clamp(64px,9.6vw,148px)] leading-[0.86] font-[750] tracking-[-0.015em] [font-stretch:62%]"
        />

        <p className="max-w-[640px] text-[19px] leading-[1.5] text-pretty text-muted-foreground">
          Settle watches your services and pages whoever is on call. A response clock and a resolution clock start the moment an
          incident opens, and an append-only record runs until the postmortem is written.
        </p>

        <div className="flex flex-wrap items-center justify-center gap-2.5">
          <Link
            to="/login"
            className="relative inline-flex h-12 items-center overflow-hidden rounded-full bg-primary px-[26px] font-bold text-primary-foreground shadow-[0_0_0_1px_oklch(1_0_0/0.15)_inset,0_10px_30px_-8px_color-mix(in_oklch,var(--glow)_60%,transparent)] hover:no-underline"
          >
            {!reduceMotion && (
              <motion.span
                aria-hidden="true"
                className="absolute inset-y-0 left-0 w-2/5 bg-[linear-gradient(100deg,transparent,oklch(1_0_0/0.45),transparent)]"
                initial={{ x: '-120%' }}
                animate={{ x: '320%' }}
                transition={{ duration: 1.4, delay: 1.8, repeat: Infinity, repeatDelay: 2.6, ease: 'easeInOut' }}
              />
            )}
            <span className="relative">Log in</span>
          </Link>
          <button
            type="button"
            onClick={replayFromButton}
            className="inline-flex h-12 items-center gap-2 rounded-full border border-white/16 bg-white/5 px-[22px] font-semibold text-foreground backdrop-blur-[10px] hover:bg-white/10"
          >
            <RotateCcw className="size-[15px]" aria-hidden="true" />
            Replay the incident
          </button>
        </div>
        <span className="text-[13px] text-muted-foreground">
          New team? <Link to="/signup">Create an org</Link>
        </span>
      </div>

      <IncidentReplay replay={replay} />
    </section>
  )
}
