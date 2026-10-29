import type { Service } from '@/api/services'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

/** Picks the service a page shows; pages keep the choice in the URL, so a link opens the same one. */
export function ServicePicker({
  services,
  chosen,
  onChoose,
}: {
  services: Service[]
  chosen: Service | undefined
  onChoose: (serviceId: string) => void
}) {
  return (
    <div className="flex w-[260px] flex-col gap-2">
      <Label htmlFor="service">Service</Label>
      <Select value={chosen ? String(chosen.id) : ''} onValueChange={onChoose}>
        <SelectTrigger id="service" className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {services.map((service) => (
            <SelectItem key={service.id} value={String(service.id)}>
              {service.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}
