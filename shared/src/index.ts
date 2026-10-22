/**
 * Code both clients need, in one place.
 *
 * <p>`http.ts` was 192 lines duplicated byte-for-byte between `web` and
 * `admin-web`. Two copies of a validation layer is two places to fix a parser
 * bug and two chances to fix only one of them — and the whole point of that
 * layer is that it is the single written-down description of what the backend
 * returns.
 *
 * <p>Published as TypeScript source rather than as a build output: both
 * consumers are Vite apps that transpile it anyway, and a build step here would
 * add a stale-artifact failure mode for no benefit.
 *
 * <p>Deliberately narrow. The UI helpers were *not* moved: the two apps have
 * different products, different conventions and only superficially similar
 * functions, and a shared package that accumulates whatever two callers happen
 * to share is how you end up with a second, worse standard library.
 */
export * from './http';

// The schema combinators. `object`, `string` and the rest are common words, so
// callers are expected to import this namespaced: `import { s } from
// '@short/shared'` then `s.object({ … })`.
export * as s from './schema';
export type { Infer, Schema } from './schema';
