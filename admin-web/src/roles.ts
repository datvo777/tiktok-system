// The staff roles, as the server defines them (Roles.java). The server enforces the split; these only
// decide what the console shows.
const ADMIN = 'ADMIN';
const MODERATOR = 'MODERATOR';

/** Works the review and report queues: a moderator, or an admin, who can do everything a moderator can. */
export const isStaff = (roles: string[] | undefined): boolean =>
  Boolean(roles?.includes(MODERATOR) || roles?.includes(ADMIN));

/** Also holds what affects a whole account, cannot be undone, or reads the audit trail. */
export const isAdmin = (roles: string[] | undefined): boolean => Boolean(roles?.includes(ADMIN));
