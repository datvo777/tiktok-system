package com.shortvideo.social.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.api.AccountView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Existence non-disclosure: a suspended (or otherwise ineligible) account must
 * answer exactly like an account id that was never registered at all, both for
 * a profile lookup and for a follow attempt — reporting the real state is the
 * one place this used to leak who had been actioned.
 */
class SocialServiceTest {

    private static AccountView suspendedAccount(String accountId) {
        return new AccountView(accountId, "Some Name", "handle", null, AccountState.SUSPENDED, 1L, Instant.now());
    }

    @Test
    void profileForSuspendedAndUnknownAccountProduceTheIdenticalNotFoundResponse() {
        AccountDirectory accountDirectory = mock(AccountDirectory.class);
        SocialService service = new SocialService(null, null, accountDirectory, null);

        String suspendedId = UUID.randomUUID().toString();
        when(accountDirectory.find(suspendedId)).thenReturn(Optional.of(suspendedAccount(suspendedId)));

        String unknownId = UUID.randomUUID().toString();
        when(accountDirectory.find(unknownId)).thenReturn(Optional.empty());

        Throwable suspended = catchThrowable(() -> service.profile(suspendedId, null));
        Throwable unknown = catchThrowable(() -> service.profile(unknownId, null));

        assertThat(suspended).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(unknown).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(suspended.getMessage()).isEqualTo(unknown.getMessage());
    }

    @Test
    void followOfSuspendedAndUnknownAccountProduceTheIdenticalNotFoundResponse() {
        AccountDirectory accountDirectory = mock(AccountDirectory.class);
        SocialService service = new SocialService(null, null, accountDirectory, null);
        String followerId = UUID.randomUUID().toString();

        String suspendedId = UUID.randomUUID().toString();
        when(accountDirectory.find(suspendedId)).thenReturn(Optional.of(suspendedAccount(suspendedId)));

        String unknownId = UUID.randomUUID().toString();
        when(accountDirectory.find(unknownId)).thenReturn(Optional.empty());

        Throwable suspended = catchThrowable(() -> service.follow(followerId, suspendedId));
        Throwable unknown = catchThrowable(() -> service.follow(followerId, unknownId));

        assertThat(suspended).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(unknown).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(suspended.getMessage()).isEqualTo(unknown.getMessage());
    }
}
