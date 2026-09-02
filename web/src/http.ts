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

// ---------------------------------------------------------------- validators
//
// Hand-written rather than a schema library: the surface is small, and these
// double as the single written-down description of what the backend actually
// returns. Each throws ContractError naming the field that was wrong, so a
// backend change shows up as a precise message instead of "cannot read
// properties of undefined".

function fail(context: string, field: string, expected: string, actual: unknown): never {
  throw new ContractError(`${context}.${field}: expected ${expected}, got ${JSON.stringify(actual)}`);
}

export function obj(context: string, value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail(context, '<root>', 'an object', value);
  }
  return value as Record<string, unknown>;
}

export function str(context: string, source: Record<string, unknown>, field: string): string {
  const value = source[field];
  if (typeof value !== 'string') fail(context, field, 'a string', value);
  return value;
}

export function num(context: string, source: Record<string, unknown>, field: string): number {
  const value = source[field];
  if (typeof value !== 'number' || Number.isNaN(value)) fail(context, field, 'a number', value);
  return value;
}

export function bool(context: string, source: Record<string, unknown>, field: string): boolean {
  const value = source[field];
  if (typeof value !== 'boolean') fail(context, field, 'a boolean', value);
  return value;
}

export function nullableStr(
  context: string,
  source: Record<string, unknown>,
  field: string,
): string | null {
  const value = source[field];
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') fail(context, field, 'a string or null', value);
  return value;
}

export function nullableNum(
  context: string,
  source: Record<string, unknown>,
  field: string,
): number | null {
  const value = source[field];
  if (value === null || value === undefined) return null;
  if (typeof value !== 'number') fail(context, field, 'a number or null', value);
  return value;
}

export function arr(context: string, value: unknown): unknown[] {
  if (!Array.isArray(value)) fail(context, '<root>', 'an array', value);
  return value;
}

export function strMap(context: string, source: Record<string, unknown>, field: string): Record<string, string> {
  const value = source[field];
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail(context, field, 'an object', value);
  }
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    if (typeof v !== 'string') fail(context, `${field}.${k}`, 'a string', v);
    out[k] = v;
  }
  return out;
}

/**
 * Keeps a union honest: an unrecognised member is a contract violation, not a
 * value to render. Without this, a new backend enum value reaches the UI typed as
 * something it is not.
 */
export function oneOf<T extends string>(
  context: string,
  source: Record<string, unknown>,
  field: string,
  allowed: readonly T[],
): T {
  const value = str(context, source, field);
  if (!(allowed as readonly string[]).includes(value)) {
    fail(context, field, `one of ${allowed.join(' | ')}`, value);
  }
  return value as T;
}
