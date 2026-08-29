package com.shortvideo.account.api;

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
}
