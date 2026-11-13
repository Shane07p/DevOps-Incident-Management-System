import { FlowSection } from './landing/FlowSection'
import { Hero } from './landing/Hero'
import { IntegrationsSection } from './landing/IntegrationsSection'
import { useLandingKit } from './landing/kit'
import { LandingFooter } from './landing/LandingFooter'
import { LandingNav } from './landing/LandingNav'
import { MonitoringSection } from './landing/MonitoringSection'
import { ProductSection } from './landing/ProductSection'
import { QuestionsSection } from './landing/QuestionsSection'
import { SettledSection } from './landing/SettledSection'

/** The public product page. It calls no API; the incidents and people on it are illustrations. */
export function LandingPage() {
  const styled = useLandingKit()

  return (
    // Hidden until the kit's styles arrive, so the unstyled markup never flashes.
    <div style={styled ? undefined : { visibility: 'hidden' }}>
      <LandingNav />
      <main id="top">
        <Hero />
        <ProductSection />
        <FlowSection />
        <MonitoringSection />
        <SettledSection />
        <IntegrationsSection />
        <QuestionsSection />
      </main>
      <LandingFooter />
    </div>
  )
}
