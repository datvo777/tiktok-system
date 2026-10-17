/**
 * The one place a fetch response is turned into either data or an error.
 *
 * <p>Every call previously ended in `return response.json()` widened to a declared
 * return type. That type was a promise, not a check: `response.json()` is `any`,
 * so a payload that did not match the declared shape flowed through the app
 * untouched until something dereferenced a field that was not there — and with no
 * error boundary, that surfaced as a blank page rather than a handled failure.
 *
 * <p>Failures also collapsed into `new Error(detail)`, so a caller could not tell
 * 401 from 500. `ApiError` carries the status, which is what lets the app react to
 * an expired session instead of showing a generic red string.
 *
 * <p>The hand-written field validators that used to live below have moved to
 * `./schema`, where a field is declared once and its TypeScript type is inferred
 * from the declaration rather than written out a second time beside it.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** From the `Retry-After` header, when the server sent one in seconds. */
    readonly retryAfterMs?: number,
    /** The problem's `code`, when the server gave one: a stable name for a cause the status alone cannot tell apart. */
    readonly code?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** The server says the same request may succeed shortly: throttled, or a dependency is briefly down. */
  get isTransient(): boolean {
    return this.status === 429 || this.status === 502 || this.status === 503 || this.status === 504;
  }
}

function parseRetryAfter(response: Response): number | undefined {
  const seconds = Number(response.headers.get('Retry-After'));
  // The HTTP-date form is not worth parsing here; callers fall back to their own backoff.
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : undefined;
}

/** Thrown when the server's payload does not match what this client expects. */
export class ContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ContractError';
  }
}

async function readProblem(response: Response): Promise<{ message: string; code?: string }> {
  try {
    const problem: unknown = await response.json();
    if (problem && typeof problem === 'object') {
      const { detail, title, code } = problem as { detail?: unknown; title?: unknown; code?: unknown };
      const message = typeof detail === 'string' ? detail : typeof title === 'string' ? title : undefined;
      if (message !== undefined) return typeof code === 'string' ? { message, code } : { message };
    }
  } catch {
    // Body was absent or not JSON; the status is all we have.
  }
  return { message: `HTTP ${response.status}` };
}

async function apiError(response: Response): Promise<ApiError> {
  const { message, code } = await readProblem(response);
  return new ApiError(response.status, message, parseRetryAfter(response), code);
}

/**
 * Runs a request and validates the payload before it reaches the app.
 *
 * @param parse narrows the untyped payload. Throw `ContractError` from here — or
 *   return the value — so a shape mismatch is reported at the boundary where it
 *   can still be explained, rather than as a crash three components later.
 */
export async function request<T>(
  path: string,
  parse: (payload: unknown) => T,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) {
    throw await apiError(response);
  }
  if (response.status === 204) {
    return parse(undefined);
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ContractError(`${path} did not return JSON`);
  }
  return parse(payload);
}

/** A request whose success is the status alone. */
export async function requestNoContent(path: string, init?: RequestInit): Promise<void> {
  const response = await fetch(path, init);
  if (!response.ok) {
    throw await apiError(response);
  }
}

export function jsonBody(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}
