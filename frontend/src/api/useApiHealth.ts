import { useQuery } from '@tanstack/react-query'
import { api } from './client'

export function useApiHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: async () => {
      const { data } = await api.GET('/v1/health')
      if (data === undefined) {
        throw new Error('The API did not answer the health check')
      }
      return data
    },
    refetchInterval: 30_000,
    retry: false,
  })
}
