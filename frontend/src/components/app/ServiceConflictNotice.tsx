import { Button } from '@/components/ui/button'

export function ServiceConflictNotice({ onReload, loading }: { onReload: () => void; loading: boolean }) {
  return (
    <div role="alert" className="flex flex-col items-start gap-2 text-sm text-destructive">
      <p>This service was changed by someone else. Reload to see the latest.</p>
      <Button type="button" variant="outline" onClick={onReload} disabled={loading}>
        {loading ? 'Reloading…' : 'Reload'}
      </Button>
    </div>
  )
}
