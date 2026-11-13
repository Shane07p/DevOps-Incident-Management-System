import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DeclareIncidentForm } from './DeclareIncidentForm'

/** The preview's declare dialog: open from the top bar, the C key, the overview, or the incident list. */
export function DeclareIncidentDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100dvh-2rem)] max-w-[530px] overflow-y-auto rounded-[20px] p-6">
        <DialogHeader>
          <DialogTitle className="text-[20px] font-medium tracking-[-0.02em]">Declare an incident</DialogTitle>
          <DialogDescription>Start with what you know. Whoever acknowledges first becomes Lead.</DialogDescription>
        </DialogHeader>
        {/* Mounted only while open, so each declaration starts from an empty form. */}
        {open && <DeclareIncidentForm onCancel={() => onOpenChange(false)} onDeclared={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}
