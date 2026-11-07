import createClient from 'openapi-fetch'
import { csrfHeaders, SAFE_METHODS } from './csrf'
import type { paths } from './schema'

// In development Vite proxies the API paths, so the page origin is the API origin; in production
// the API lives on its own subdomain (ADR-0008).
export const apiBaseUrl: string =
  import.meta.env.VITE_API_BASE_URL ?? window.location.origin

// Typed from api/openapi.yaml: a path, parameter or response that the spec does not declare
// fails type-checking.
export const api = createClient<paths>({ baseUrl: apiBaseUrl })

api.use({
  onRequest({ request }) {
    if (!SAFE_METHODS.has(request.method)) {
      for (const [name, value] of Object.entries(csrfHeaders())) {
        request.headers.set(name, value)
      }
    }
    return request
  },
})
