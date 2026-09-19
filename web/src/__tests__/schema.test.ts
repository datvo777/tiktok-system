import { describe, expect, it } from 'vitest';
import { ContractError, s, type Infer } from '@short/shared';

describe('schema combinators', () => {
  it('parses an object and names the offending field on failure', () => {
    const video = s.object({ videoId: s.string, likeCount: s.number, liked: s.boolean });

    expect(video.parse('video', { videoId: 'v1', likeCount: 3, liked: true })).toEqual({
      videoId: 'v1',
      likeCount: 3,
      liked: true,
    });
    expect(() => video.parse('video', { videoId: 7, likeCount: 3, liked: true })).toThrow(
      /video\.videoId: expected a string, got 7/,
    );
  });

  it('rejects NaN, which is what a bad numeric field parses to', () => {
    expect(() => s.number.parse('counts.likeCount', Number.NaN)).toThrow(ContractError);
  });

  it('treats an absent field and an explicit null alike', () => {
    const nullableString = s.nullable(s.string);

    expect(nullableString.parse('x', null)).toBeNull();
    expect(nullableString.parse('x', undefined)).toBeNull();
    expect(nullableString.parse('x', 'here')).toBe('here');
  });

  it('reports the index of a bad array element', () => {
    expect(() => s.array(s.string).parse('feed.items', ['a', 2])).toThrow(/feed\.items\[1\]/);
  });

  // A new backend enum value must not reach the UI typed as something it is not.
  it('rejects a union member it does not know', () => {
    const state = s.oneOf('READY', 'FAILED');

    expect(state.parse('video.state', 'READY')).toBe('READY');
    expect(() => state.parse('video.state', 'ARCHIVED')).toThrow(/one of READY \| FAILED/);
  });

  it('validates every value of a string map', () => {
    expect(s.stringMap().parse('fields', { key: 'v' })).toEqual({ key: 'v' });
    expect(() => s.stringMap().parse('fields', { key: 7 })).toThrow(/fields\.key/);
  });

  it('rejects an array or null where an object is expected', () => {
    const empty = s.object({});

    expect(() => empty.parse('x', [])).toThrow(ContractError);
    expect(() => empty.parse('x', null)).toThrow(ContractError);
  });

  it('nests, so a field path reads end to end', () => {
    const page = s.object({ items: s.array(s.object({ id: s.string })) });

    expect(() => page.parse('feed', { items: [{ id: 'a' }, { id: 9 }] })).toThrow(
      /feed\.items\[1\]\.id: expected a string/,
    );
  });

  /**
   * The point of the whole exercise: the type comes from the schema, so there is
   * no second declaration that can drift. If this stops compiling, the inference
   * is broken even if every runtime test still passes.
   */
  it('infers the type from the schema', () => {
    const account = s.object({
      accountId: s.string,
      handle: s.string,
      bio: s.nullable(s.string),
      roles: s.array(s.string),
      state: s.oneOf('ACTIVE', 'SUSPENDED'),
    });

    const parsed: Infer<typeof account> = account.parse('account', {
      accountId: 'a1',
      handle: 'dat',
      bio: null,
      roles: ['USER'],
      state: 'ACTIVE',
    });

    // Each of these is a compile-time assertion as much as a runtime one.
    const id: string = parsed.accountId;
    const bio: string | null = parsed.bio;
    const roles: string[] = parsed.roles;
    const state: 'ACTIVE' | 'SUSPENDED' = parsed.state;

    expect([id, bio, roles, state]).toEqual(['a1', null, ['USER'], 'ACTIVE']);
  });
});
