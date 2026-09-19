package com.shortvideo.account.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The account module's synchronous interface (brief section 9).
 *
 * <p>Other modules call this. They do not read the account schema, and they do
 * not cache the result as an authorisation decision — a missing account is
 * unknown state and denies (Rule 9).
 */
public interface AccountDirectory {

    Optional<AccountView> find(String accountId);

    /**
     * As {@link #find}, for a whole set of ids in one query.
     *
     * <p>Exists for callers rendering a list of other people's content -- a page
     * of comments, say -- where resolving names one at a time is a round trip per
     * row. Ids that do not resolve are simply absent from the result rather than
     * mapping to null, so a caller iterating the map sees only accounts that
     * exist.
     *
     * @return display names keyed by account id.
     */
    Map<String, AccountView> findAll(Collection<String> accountIds);
}
