import type { Control } from 'react-hook-form'
import { SEVERITIES } from '@/api/services'
import { SeverityBadge } from '@/components/app/badges'
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import type { ServiceFormValues } from './serviceForm'

const TARGETS = [
  { key: 'responseMinutes', label: 'response' },
  { key: 'resolutionMinutes', label: 'resolution' },
] as const

/** Response and resolution minutes for each severity. Incidents copy these when they are created. */
export function SlaTargetGrid({ control }: { control: Control<ServiceFormValues> }) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-2 text-sm font-medium">SLA targets</legend>
      <div className="grid grid-cols-[auto_1fr_1fr] items-start gap-x-3 gap-y-2">
        <span aria-hidden="true" />
        <span aria-hidden="true" className="text-xs font-medium text-muted-foreground">
          Response (min)
        </span>
        <span aria-hidden="true" className="text-xs font-medium text-muted-foreground">
          Resolution (min)
        </span>
        {SEVERITIES.map((severity) => (
          <div key={severity} className="contents">
            <SeverityBadge severity={severity} className="mt-[7px]" />
            {TARGETS.map((target) => (
              <FormField
                key={target.key}
                control={control}
                name={`targets.${severity}.${target.key}`}
                render={({ field }) => (
                  <FormItem className="gap-1">
                    <FormLabel className="sr-only">{`${severity} ${target.label} target, in minutes`}</FormLabel>
                    <FormControl>
                      <Input type="number" inputMode="numeric" min={1} step={1} className="font-mono" {...field} />
                    </FormControl>
                    <FormMessage className="text-xs" />
                  </FormItem>
                )}
              />
            ))}
          </div>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">
        Resolution can't be shorter than response. 60 minutes is an hour; 1440 is a day.
      </p>
    </fieldset>
  )
}
