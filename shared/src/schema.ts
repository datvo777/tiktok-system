/**
 * A tiny schema combinator library, so a field is declared once.
 *
 * <h2>The problem this solves</h2>
 *
 * The client validated every response at the boundary — genuinely the right
 * instinct, and rare. But it did so by writing each field twice: once in a
 * TypeScript type and once in a hand-written parser. Nine hundred lines of
 * `api.ts` were mostly that duplication, and nothing made the two agree. Delete
 * a field from the type and the parser still reads it; add one to the parser and
 * the type never learns about it. The compiler cannot help, because the two
 * declarations are unrelated pieces of code that merely happen to be adjacent.
 *
 * <p>Here the schema is the single declaration and the type is *inferred* from
 * it, so the two cannot disagree — there is only one of them.
 *
 * <h2>Why not a dependency</h2>
 *
 * Zod would do this and more. This is ~120 lines with no supply chain, produces
 * the same `ContractError` messages the app already surfaces, and covers exactly
 * the shapes this API returns. Reach for the dependency when the API needs
 * something below — unions of objects, refinements, transforms — rather than
 * ahead of it.
 *
 * <h2>Why not generate from OpenAPI</h2>
 *
 * The backend does publish a schema, and generating from it would remove even
 * this. It also means a build step that needs the application running to emit
 * the document, and a generated file in the tree that has to be regenerated and
 * reviewed. That is a real option and a reasonable next move; it is a different,
 * larger decision than removing the duplication, which is what this does.
 */

import { ContractError } from './http';

/** A parser that turns unknown input into a `T`, or throws `ContractError`. */
export type Schema<T> = {
  /** @param context dotted path used in the error message, e.g. `feed.items[3]`. */
  parse(context: string, value: unknown): T;
};

/** The type a schema produces. This is what removes the second declaration. */
export type Infer<S> = S extends Schema<infer T> ? T : never;

function fail(context: string, expected: string, actual: unknown): never {
  throw new ContractError(`${context}: expected ${expected}, got ${JSON.stringify(actual)}`);
}

function schema<T>(parse: (context: string, value: unknown) => T): Schema<T> {
  return { parse };
}

export const string: Schema<string> = schema((context, value) =>
  typeof value === 'string' ? value : fail(context, 'a string', value),
);

export const number: Schema<number> = schema((context, value) =>
  // NaN is a number to `typeof` and never a valid one here: it is what a bad
  // numeric field parses to, so letting it through defeats the check.
  typeof value === 'number' && !Number.isNaN(value) ? value : fail(context, 'a number', value),
);

export const boolean: Schema<boolean> = schema((context, value) =>
  typeof value === 'boolean' ? value : fail(context, 'a boolean', value),
);

/** Treats an absent field and an explicit null alike: both mean "not set". */
export function nullable<T>(inner: Schema<T>): Schema<T | null> {
  return schema((context, value) =>
    value === null || value === undefined ? null : inner.parse(context, value),
  );
}

export function array<T>(inner: Schema<T>): Schema<T[]> {
  return schema((context, value) => {
    if (!Array.isArray(value)) fail(context, 'an array', value);
    return value.map((item, i) => inner.parse(`${context}[${i}]`, item));
  });
}

/**
 * Keeps a union honest: an unrecognised member is a contract violation, not a
 * value to render. Without this, a new backend enum value reaches the UI typed
 * as something it is not.
 */
export function oneOf<const T extends readonly string[]>(...allowed: T): Schema<T[number]> {
  return schema((context, value) => {
    const parsed = string.parse(context, value);
    if (!allowed.includes(parsed)) fail(context, `one of ${allowed.join(' | ')}`, parsed);
    return parsed as T[number];
  });
}

/** A JSON object of string values, e.g. the presigned upload's form fields. */
export function stringMap(): Schema<Record<string, string>> {
  return schema((context, value) => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      fail(context, 'an object', value);
    }
    const out: Record<string, string> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      out[key] = string.parse(`${context}.${key}`, entry);
    }
    return out;
  });
}

/** An endpoint whose success is its status alone. */
export const nothing: Schema<void> = schema(() => undefined);

type Shape = Record<string, Schema<unknown>>;

/**
 * The workhorse. Field names come from the object's keys, so the error message
 * names the field without anyone repeating it in a string.
 */
export function object<S extends Shape>(shape: S): Schema<{ [K in keyof S]: Infer<S[K]> }> {
  return schema((context, value) => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      fail(context, 'an object', value);
    }
    const source = value as Record<string, unknown>;
    const out = {} as { [K in keyof S]: Infer<S[K]> };
    for (const key of Object.keys(shape) as (keyof S & string)[]) {
      out[key] = (shape[key] as Schema<unknown>).parse(`${context}.${key}`, source[key]) as Infer<S[typeof key]>;
    }
    return out;
  });
}
