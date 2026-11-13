package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;

import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationSubjects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;

/**
 * What the public feed does with credentials, and what its cache does under load.
 * The feed is {@code permitAll}, but the authentication filter still runs first, so
 * a token the filter rejects does not produce a 401 here -- the caller is simply a
 * stranger. These pin that, and the cost and cache behaviour around it.
 */
class FeedAuthenticationIT extends AbstractAuthRedisIT {

    private static final GenericContainer<?> REDIS = newRedis();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        pointAt(registry, REDIS);
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Autowired StringRedisTemplate redis;

    @MockitoSpyBean EligibilityDirectory eligibility;
    @MockitoSpyBean DurableRevocationReader revocations;

    // ------------------------------------------------------------------ H2

    @Test
    void aLoggedOutTokenIsAStrangerOnTheFeedButRefusedEverywhereElse() {
        Session viewer = registerAndLogin();
        assertThat(status(HttpMethod.POST, "/api/v1/auth/logout", viewer.token())).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", viewer.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertStrangerOnFeed(viewer);
    }

    @Test
    void anExpiredTokenIsAStrangerOnTheFeedButRefusedEverywhereElse() {
        Session viewer = registerAndLogin();
        String expired = expiredTokenFor(viewer.accountId());

        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", expired)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertStrangerOnFeed(new Session(viewer.accountId(), expired));
    }

    @Test
    void aSuspendedAccountsTokenIsAStrangerOnTheFeedButRefusedEverywhereElse() {
        Session viewer = registerAndLogin();
        assertThat(suspend(tokenWithRoles(java.util.Set.of("ADMIN")), viewer.accountId())).isEqualTo(HttpStatus.OK);

        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", viewer.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertStrangerOnFeed(viewer);
    }

    private void assertStrangerOnFeed(Session viewer) {
        redis.delete("feed:ranking:anonymous");

        assertThat(status(HttpMethod.GET, "/api/v1/feed", viewer.token())).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/feed?scope=FOLLOWING", viewer.token())).isEqualTo(HttpStatus.OK);

        // Served the shared signed-out ranking, not one keyed to the account behind the token.
        assertThat(redis.hasKey("feed:ranking:anonymous")).isTrue();
        assertThat(redis.hasKey("feed:ranking:" + viewer.accountId())).isFalse();
    }

    // ------------------------------------------------------------------ H4

    @Test
    void aSignedInViewerCostsOneDurableAccountCheckPerRequestEvenWhenTheRankingIsCached() {
        Session viewer = registerAndLogin();
        org.mockito.Mockito.clearInvocations(eligibility, revocations);

        assertThat(status(HttpMethod.GET, "/api/v1/feed?page=0", viewer.token())).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/feed?page=1", viewer.token())).isEqualTo(HttpStatus.OK);

        // Page 0 ranks; page 1 slices the cached ranking. Authentication still asks PostgreSQL both times.
        assertThat(invocations(eligibility, "findEligibleVideosWithEligibleCreators")).isEqualTo(1);
        long accountChecks = mockingDetails(revocations).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("isActive"))
                .filter(i -> RevocationSubjects.ACCOUNT.equals(i.getArgument(0)))
                .filter(i -> viewer.accountId().equals(i.getArgument(1)))
                .count();
        assertThat(accountChecks).isEqualTo(2);
    }

    // ------------------------------------------------------------------ H5

    /**
     * Characterisation, not a requirement: nothing coordinates concurrent misses, so each
     * one runs the whole ranking. When single-flight is added this should drop to 1 and the
     * assertion flips with it.
     */
    @Test
    void concurrentMissesEachRecomputeTheRanking() throws Exception {
        redis.delete("feed:ranking:anonymous");
        org.mockito.Mockito.clearInvocations(eligibility);
        // Hold each computation open so the requests genuinely overlap.
        doAnswer(invocation -> {
                    Thread.sleep(300);
                    return invocation.callRealMethod();
                })
                .when(eligibility)
                .findEligibleVideosWithEligibleCreators(anyInt());

        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return status(HttpMethod.GET, "/api/v1/feed", null);
                }));
            }
            go.countDown();
            for (Future<HttpStatus> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
            }
        } finally {
            // Also runs when an assertion or timeout above throws, so no worker is left parked on the latch.
            pool.shutdownNow();
        }

        assertThat(invocations(eligibility, "findEligibleVideosWithEligibleCreators")).isGreaterThan(1);
    }

    // ------------------------------------------------------------------ H6

    @Test
    void anEmptyRankingIsCachedAndItsTtlIsNotExtendedByReads() throws Exception {
        redis.delete("feed:ranking:anonymous");
        org.mockito.Mockito.clearInvocations(eligibility);
        doReturn(List.of()).when(eligibility).findEligibleVideosWithEligibleCreators(anyInt());

        assertThat(status(HttpMethod.GET, "/api/v1/feed", null)).isEqualTo(HttpStatus.OK);
        Long first = redis.getExpire("feed:ranking:anonymous", TimeUnit.MILLISECONDS);
        Thread.sleep(1200);
        assertThat(status(HttpMethod.GET, "/api/v1/feed", null)).isEqualTo(HttpStatus.OK);
        Long second = redis.getExpire("feed:ranking:anonymous", TimeUnit.MILLISECONDS);

        // The empty result was cached: the second request did not look again, so a video published
        // in between stays invisible until the key expires.
        assertThat(invocations(eligibility, "findEligibleVideosWithEligibleCreators")).isEqualTo(1);
        assertThat(first).isBetween(1L, 30_000L);
        assertThat(second).isLessThan(first);
    }

    private static long invocations(Object spy, String method) {
        return mockingDetails(spy).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(method))
                .count();
    }
}
