package com.shortvideo.account.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Single-use codes for signing in without the authenticator. Shown once; only a hash is kept. */
final class RecoveryCodes {

    static final int COUNT = 10;
    private static final int LENGTH = 10;
    // No 0/O/1/I: they are read aloud and copied by hand.
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    /** Codes as shown to the person, in two groups of five. */
    static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            StringBuilder code = new StringBuilder(LENGTH + 1);
            for (int j = 0; j < LENGTH; j++) {
                if (j == LENGTH / 2) {
                    code.append('-');
                }
                code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /** Case, dashes and spaces do not matter when typing one in. */
    static String normalise(String code) {
        return code == null ? "" : code.toUpperCase().replace("-", "").replace(" ", "").trim();
    }

    static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalise(code).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
