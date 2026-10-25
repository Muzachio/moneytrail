import { useApiHealth } from '@/api/useApiHealth'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

export function ApiStatus() {
  const { data, isPending } = useApiHealth()

  if (isPending) {
    return <Badge variant="outline">Checking API…</Badge>
  }

  const online = data?.status === 'UP'
  return (
    <Badge variant={online ? 'secondary' : 'destructive'}>
      <span
        aria-hidden
        className={cn(
          'size-2 rounded-full',
          online ? 'bg-emerald-500' : 'bg-current',
        )}
      />
      {online ? 'API online' : 'API offline'}
    </Badge>
  )
}
