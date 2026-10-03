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
}

/** Thrown when the server's payload does not match what this client expects. */
export class ContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ContractError';
  }
}

async function readProblemDetail(response: Response): Promise<string> {
  try {
    const problem: unknown = await response.json();
    if (problem && typeof problem === 'object') {
      const { detail, title } = problem as { detail?: unknown; title?: unknown };
      if (typeof detail === 'string') return detail;
      if (typeof title === 'string') return title;
    }
  } catch {
    // Body was absent or not JSON; the status is all we have.
  }
  return `HTTP ${response.status}`;
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
    throw new ApiError(response.status, await readProblemDetail(response));
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
    throw new ApiError(response.status, await readProblemDetail(response));
  }
}

export function jsonBody(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}
