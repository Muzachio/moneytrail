import type { components } from './schema'

export type Problem = components['schemas']['Problem']

/**
 * An answer from the API that is not a success. `code` is the problem details' stable code
 * (`category_name_taken`, `bad_request`…): forms decide from it what to tell the person, never from
 * `detail`, which is English text meant for developers.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined

  constructor(status: number, problem: unknown) {
    const { code, detail } = isProblem(problem) ? problem : {}
    super(detail ?? `The API answered ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

function isProblem(body: unknown): body is Partial<Problem> {
  return typeof body === 'object' && body !== null
}

/**
 * The body of a successful openapi-fetch answer, or an `ApiError` with the problem the API sent
 * instead, so TanStack Query sees a failed request as an error.
 */
export function dataOrThrow<T>(result: {
  data?: T
  error?: unknown
  response: Response
}): T {
  if (!result.response.ok || result.data === undefined) {
    throw new ApiError(result.response.status, result.error)
  }
  return result.data
}
