import createClient from 'openapi-fetch'
import type { paths } from './schema'

// Typed from api/openapi.yaml: a path, parameter or response that the spec does not declare
// fails type-checking. In development Vite proxies /v1 to the backend, so the page origin is
// the API origin; in production the API lives on its own subdomain (ADR-0008).
export const api = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? window.location.origin,
})
