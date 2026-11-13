import { MotionConfig } from 'motion/react'
import { FlowSection } from './landing/FlowSection'
import { Hero } from './landing/Hero'
import { IntegrationsSection } from './landing/IntegrationsSection'
import { LandingFooter } from './landing/LandingFooter'
import { LandingHeader } from './landing/LandingHeader'
import { MonitoringSection } from './landing/MonitoringSection'
import { ProductSection } from './landing/ProductSection'
import { QuestionsSection } from './landing/QuestionsSection'
import { RecordSection } from './landing/RecordSection'

/**
 * The public product page. It is always dark, like the design it follows, and calls no API: the
 * incidents and people on it are illustrations. Motion follows the visitor's reduced-motion setting.
 */
export function LandingPage() {
  return (
    <MotionConfig reducedMotion="user">
      <div className="dark overflow-x-clip bg-background text-base text-foreground">
        <LandingHeader />
        <main>
          <Hero />
          <ProductSection />
          <FlowSection />
          <MonitoringSection />
          <RecordSection />
          <IntegrationsSection />
          <QuestionsSection />
        </main>
        <LandingFooter />
      </div>
    </MotionConfig>
  )
}
