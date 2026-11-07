import { afterEach, describe, expect, it } from 'vitest'
import { csrfHeaders } from './csrf'

describe('csrfHeaders', () => {
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('echoes the XSRF-TOKEN cookie as the X-XSRF-TOKEN header', () => {
    document.cookie = 'XSRF-TOKEN=abc-123'

    expect(csrfHeaders()).toEqual({ 'X-XSRF-TOKEN': 'abc-123' })
  })

  it('sends nothing before the API has set the cookie', () => {
    expect(csrfHeaders()).toEqual({})
  })
})
