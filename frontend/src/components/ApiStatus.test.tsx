import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { renderApp } from '@/test/renderApp'

describe('ApiStatus', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the API as online when the health check answers UP', async () => {
    const get = vi
      .spyOn(api, 'GET')
      .mockResolvedValue({ data: { status: 'UP' } } as never)

    renderApp()

    expect(await screen.findByText('API online')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/v1/health')
  })

  it('shows the API as offline when the health check fails', async () => {
    vi.spyOn(api, 'GET').mockRejectedValue(new TypeError('Failed to fetch'))

    renderApp()

    expect(await screen.findByText('API offline')).toBeInTheDocument()
  })
})
