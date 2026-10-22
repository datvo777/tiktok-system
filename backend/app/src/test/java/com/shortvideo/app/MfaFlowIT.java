package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The second factor end to end, with the factor switched on: enrolling, signing in with a code, what the
 * session may reach, and the ways a code or a pending sign-in can be refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MfaFlowIT {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ADMIN_ONLY_ROUTE = "/internal/v1/videos/pending";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
        registry.add("shortvideo.register-rate-limit.enabled", () -> "false");
        registry.add("shortvideo.mfa.enabled", () -> "true");
        // The published placeholder; the "test" profile permits it the way "local" does.
        registry.add("shortvideo.mfa.encryption-key", () -> "bG9jYWwtZGV2LW1mYS1rZXktZG8tbm90LXVzZS14eXo=");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @LocalServerPort int port;

    @BeforeEach
    void clearCounters() {
        redis.keys("login:*").forEach(redis::delete);
        redis.keys("register:*").forEach(redis::delete);
        redis.keys("mfa:*").forEach(redis::delete);
    }

    // ------------------------------------------------------------------ before there is a factor

    @Test
    void anAdminWithoutASecondFactorIsRefusedAtInternalAndToldWhy() {
        Admin admin = newAdmin();
        String token = (String) login(admin).getBody().get("token");

        ResponseEntity<String> refused = get(ADMIN_ONLY_ROUTE, token);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("MFA_REQUIRED");
    }

    @Test
    void enrolmentNeedsThePasswordAgainNotJustASession() {
        Admin admin = newAdmin();
        String token = (String) login(admin).getBody().get("token");

        ResponseEntity<String> wrong = post("/api/v1/auth/mfa/totp/enroll", token, Map.of("password", "not-the-password-at-all"));

        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wrong.getBody()).contains("PASSWORD_INCORRECT");
        assertThat(post("/api/v1/auth/mfa/totp/enroll", null, Map.of("password", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void confirmingWithoutStartingIsRefused() {
        Admin admin = newAdmin();
        String token = (String) login(admin).getBody().get("token");

        ResponseEntity<String> refused = post("/api/v1/auth/mfa/totp/confirm", token, Map.of("code", "123456"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("MFA_NO_PENDING_ENROLLMENT");
    }

    @Test
    void aWrongCodeDoesNotActivateTheFactor() {
        Admin admin = newAdmin();
        String token = (String) login(admin).getBody().get("token");
        post("/api/v1/auth/mfa/totp/enroll", token, Map.of("password", PASSWORD));

        ResponseEntity<String> refused = post("/api/v1/auth/mfa/totp/confirm", token, Map.of("code", "000000"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refused.getBody()).contains("INVALID_MFA_CODE");
        // Still password-only: signing in again does not ask for a code.
        assertThat(login(admin).getBody()).containsKey("token");
    }

    // ------------------------------------------------------------------------- enrolling

    @Test
    void confirmingTurnsItOnReturnsRecoveryCodesAndReplacesTheSession() {
        Admin admin = newAdmin();
        String before = (String) login(admin).getBody().get("token");

        Enrolled enrolled = enrol(admin, before);

        assertThat(enrolled.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        // The new session carries the second factor, so it reaches the admin API; the one that enrolled is gone.
        assertThat(get(ADMIN_ONLY_ROUTE, enrolled.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/auth/me", before).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/auth/mfa/status", enrolled.token()).getBody())
                .contains("\"enrolled\":true").contains("\"signedInWithSecondFactor\":true");
    }

    @Test
    void enrolmentCannotBeRepeatedWhileOneIsActive() {
        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));

        ResponseEntity<String> again = post("/api/v1/auth/mfa/totp/enroll", enrolled.token(), Map.of("password", PASSWORD));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("MFA_ALREADY_ENROLLED");
    }

    @Test
    void theSecretIsStoredSealedNotInTheClear() {
        Admin admin = newAdmin();
        ResponseEntity<Map> started = postMap("/api/v1/auth/mfa/totp/enroll",
                (String) login(admin).getBody().get("token"), Map.of("password", PASSWORD));
        String secretText = (String) started.getBody().get("secret");

        byte[] stored = jdbc.queryForObject(
                "SELECT secret_enc FROM account.mfa_factor WHERE account_id = ?::uuid", byte[].class, admin.accountId());

        assertThat(started.getBody().get("otpauthUri").toString()).startsWith("otpauth://totp/Short:");
        assertThat(stored).hasSize(12 + 20 + 16);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secretText);
        assertThat(java.util.Arrays.equals(java.util.Arrays.copyOfRange(stored, 12, 32), base32(secretText))).isFalse();
    }

    // ----------------------------------------------------------------------------- signing in

    @Test
    void signingInThenAsksForACodeAndHandsOutNoSession() {
        Admin admin = newAdmin();
        enrol(admin, (String) login(admin).getBody().get("token"));

        ResponseEntity<Map> challenge = login(admin);

        assertThat(challenge.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(challenge.getBody()).containsEntry("mfaRequired", true).containsKey("mfaToken").doesNotContainKey("token");
        assertThat(challenge.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void thePendingTokenIsNotASession() {
        Admin admin = newAdmin();
        enrol(admin, (String) login(admin).getBody().get("token"));
        String pending = (String) login(admin).getBody().get("mfaToken");

        assertThat(get("/api/v1/auth/me", pending).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(ADMIN_ONLY_ROUTE, pending).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRightCodeFinishesSigningInAndTheSessionReachesInternal() {
        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));
        String pending = (String) login(admin).getBody().get("mfaToken");

        ResponseEntity<Map> done = verify(pending, Map.of("code", enrolled.nextCode()));

        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("sv_session=");
        assertThat(get(ADMIN_ONLY_ROUTE, (String) done.getBody().get("token")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aWrongCodeIsRefusedWithAStableCode() {
        Admin admin = newAdmin();
        enrol(admin, (String) login(admin).getBody().get("token"));
        String pending = (String) login(admin).getBody().get("mfaToken");

        ResponseEntity<String> refused = verifyRaw(pending, Map.of("code", "000000"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refused.getBody()).contains("INVALID_MFA_CODE");
    }

    @Test
    void aCodeThatWasAlreadyAcceptedCannotBeUsedAgain() {
        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));
        String code = enrolled.nextCode();

        assertThat(verify((String) login(admin).getBody().get("mfaToken"), Map.of("code", code)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> replay = verifyRaw((String) login(admin).getBody().get("mfaToken"), Map.of("code", code));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).contains("INVALID_MFA_CODE");
    }

    @Test
    void theCodeThatConfirmedEnrolmentCannotSignInEither() {
        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));
        String pending = (String) login(admin).getBody().get("mfaToken");

        ResponseEntity<String> refused = verifyRaw(pending, Map.of("code", enrolled.confirmedWith()));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPendingSignInIsBurnedAfterFiveWrongCodesEvenIfTheNextIsRight() {
        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));
        String pending = (String) login(admin).getBody().get("mfaToken");

        for (int i = 0; i < 5; i++) {
            assertThat(verifyRaw(pending, Map.of("code", "00000" + i)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<String> burned = verifyRaw(pending, Map.of("code", enrolled.nextCode()));

        assertThat(burned.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(burned.getBody()).contains("MFA_TOKEN_INVALID");
    }

    @Test
    void aFinishedSignInCannotBeReplayedWithTheSamePendingToken() {
        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));
        String pending = (String) login(admin).getBody().get("mfaToken");
        assertThat(verify(pending, Map.of("code", enrolled.nextCode())).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> again = verifyRaw(pending, Map.of("code", enrolled.nextCode()));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(again.getBody()).contains("MFA_TOKEN_INVALID");
    }

    @Test
    void aGarbageTokenIsRefused() {
        assertThat(verifyRaw("not-a-token", Map.of("code", "123456")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sendingBothOrNeitherKindOfCodeIsABadRequest() {
        Admin admin = newAdmin();
        enrol(admin, (String) login(admin).getBody().get("token"));
        String pending = (String) login(admin).getBody().get("mfaToken");

        assertThat(verifyRaw(pending, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(verifyRaw(pending, Map.of("code", "123456", "recoveryCode", "ABCDE-FGHJK")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ----------------------------------------------------------------------- recovery codes

    @Test
    void aRecoveryCodeSignsInOnceAndIsRecordedInTheAuditLog() {
        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));
        String recovery = enrolled.recoveryCodes().get(0);

        ResponseEntity<Map> first = verify((String) login(admin).getBody().get("mfaToken"), Map.of("recoveryCode", recovery));
        ResponseEntity<String> second =
                verifyRaw((String) login(admin).getBody().get("mfaToken"), Map.of("recoveryCode", recovery.toLowerCase().replace("-", " ")));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(ADMIN_ONLY_ROUTE, (String) first.getBody().get("token")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.admin_action WHERE actor_account_id = ?::uuid AND action = 'account.mfa_recovery_used'",
                        Integer.class, admin.accountId()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------- the session

    @Test
    void refreshingKeepsTheSecondFactorButCannotAddOne() {
        Admin passwordOnly = newAdmin();
        String plain = (String) login(passwordOnly).getBody().get("token");
        String renewedPlain = (String) postMap("/api/v1/auth/refresh", plain, null).getBody().get("token");
        assertThat(get(ADMIN_ONLY_ROUTE, renewedPlain).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Admin admin = newAdmin();
        Enrolled enrolled = enrol(admin, (String) login(admin).getBody().get("token"));
        String renewed = (String) postMap("/api/v1/auth/refresh", enrolled.token(), null).getBody().get("token");
        assertThat(get(ADMIN_ONLY_ROUTE, renewed).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anOrdinaryUserIsNotAskedForACodeAndStillCannotReachInternal() {
        Admin user = newUser();
        ResponseEntity<Map> login = login(user);

        assertThat(login.getBody()).containsKey("token").doesNotContainKey("mfaRequired");
        assertThat(get(ADMIN_ONLY_ROUTE, (String) login.getBody().get("token")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------------- helpers

    private record Admin(String email, String accountId) {}

    private record Enrolled(String token, List<String> recoveryCodes, byte[] secret, long confirmedStep) {
        /** A code that is valid now and for a step later than the one enrolment used: the window allows one ahead. */
        String nextCode() { return code(secret, confirmedStep + 1); }
        String confirmedWith() { return code(secret, confirmedStep); }
    }

    private Admin newAdmin() {
        Admin account = newUser();
        jdbc.update("UPDATE account.account SET roles = 'USER,ADMIN' WHERE email = ?", account.email());
        return account;
    }

    private Admin newUser() {
        String email = "mfa-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> created = rest.postForEntity(
                "/api/v1/accounts", Map.of("email", email, "password", PASSWORD, "displayName", "MFA test"), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        return new Admin(email, (String) created.getBody().get("accountId"));
    }

    private ResponseEntity<Map> login(Admin admin) {
        return rest.postForEntity("/api/v1/auth/login", Map.of("email", admin.email(), "password", PASSWORD), Map.class);
    }

    /** Enrols with the app's own arithmetic, so the test checks the server against an independent TOTP. */
    private Enrolled enrol(Admin admin, String token) {
        ResponseEntity<Map> started = postMap("/api/v1/auth/mfa/totp/enroll", token, Map.of("password", PASSWORD));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] secret = base32((String) started.getBody().get("secret"));
        long step = System.currentTimeMillis() / 1000 / 30;

        ResponseEntity<Map> confirmed = postMap("/api/v1/auth/mfa/totp/confirm", token, Map.of("code", code(secret, step)));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> codes = new ArrayList<>((List<String>) confirmed.getBody().get("recoveryCodes"));
        return new Enrolled((String) confirmed.getBody().get("token"), codes, secret, step);
    }

    private ResponseEntity<Map> verify(String pending, Map<String, String> body) {
        return rest.exchange("/api/v1/auth/mfa/verify", HttpMethod.POST, json(pending, body), Map.class);
    }

    private ResponseEntity<String> verifyRaw(String pending, Map<String, String> body) {
        return rest.exchange("/api/v1/auth/mfa/verify", HttpMethod.POST, json(pending, body), String.class);
    }

    private HttpEntity<Map<String, String>> json(String pending, Map<String, String> body) {
        Map<String, String> all = new java.util.HashMap<>(body);
        all.put("mfaToken", pending);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(all, headers);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) headers.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, String bearer, Object body) {
        return rest.exchange(path, HttpMethod.POST, entity(bearer, body), String.class);
    }

    private ResponseEntity<Map> postMap(String path, String bearer, Object body) {
        return rest.exchange(path, HttpMethod.POST, entity(bearer, body), Map.class);
    }

    private HttpEntity<Object> entity(String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) headers.setBearerAuth(bearer);
        return new HttpEntity<>(body, headers);
    }

    /** RFC 6238, written out here on purpose rather than calling the application's own. */
    private static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] base32(String text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c - '2' + 26;
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
