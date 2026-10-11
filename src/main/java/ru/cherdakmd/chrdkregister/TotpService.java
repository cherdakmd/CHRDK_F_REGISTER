package ru.cherdakmd.chrdkregister;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ByteArrayOutputStream;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * RFC 6238 Time-Based One-Time Password (TOTP) and RFC 4648 Base32 implementation.
 * Compatible with Google Authenticator, 2FAS, Authy, Yandex Key, and Microsoft Authenticator.
 */
public final class TotpService {
    public static final int SECRET_BYTES = 20;
    public static final int CODE_DIGITS = 6;
    public static final int TIME_STEP_SECONDS = 30;
    private static final long TIME_STEP_MILLIS = TIME_STEP_SECONDS * 1000L;
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int DIGITS_POWER = 1_000_000;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpService() {
    }

    /** Generates a new 160-bit (32-character Base32) secret key. */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /** Formats a Base32 secret in groups of 4 characters for easy manual entry. */
    public static String formatSecretForDisplay(String base32Secret) {
        if (base32Secret == null || base32Secret.isBlank()) return "";
        String clean = cleanBase32(base32Secret);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append(clean.charAt(i));
        }
        return sb.toString();
    }

    /** Builds a standard otpauth://totp/ URI for authenticator apps. */
    public static String buildOtpAuthUri(String issuer, String accountName, String base32Secret) {
        String safeIssuer = (issuer == null || issuer.isBlank()) ? "CHRDK" : issuer.trim();
        String safeAccount = (accountName == null || accountName.isBlank()) ? "player" : accountName.trim();
        String cleanSecret = cleanBase32(base32Secret);
        String label = urlEncode(safeIssuer) + ":" + urlEncode(safeAccount);
        return "otpauth://totp/" + label
                + "?secret=" + urlEncode(cleanSecret)
                + "&issuer=" + urlEncode(safeIssuer)
                + "&algorithm=SHA1&digits=" + CODE_DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    /** Normalizes a user-supplied TOTP code by removing spaces and hyphens. */
    public static String normalizeCode(String rawCode) {
        if (rawCode == null) return "";
        return rawCode.replace(" ", "").replace("-", "").trim();
    }

    /** Returns true if the supplied string is a 6-digit numeric TOTP code. */
    public static boolean isValidCodeFormat(String rawCode) {
        String normalized = normalizeCode(rawCode);
        if (normalized.length() != CODE_DIGITS) return false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Computes the 6-digit TOTP code for the given epoch timestamp in milliseconds. */
    public static String generateCode(String base32Secret, long epochMillis) {
        byte[] secretBytes = decodeBase32(base32Secret);
        long step = getTimeStep(epochMillis);
        return generateCodeAtStep(secretBytes, step);
    }

    /** Computes the 6-digit HOTP/TOTP code for an explicit 30-second time step counter. */
    public static String generateCodeAtStep(byte[] secretBytes, long timeStep) {
        if (secretBytes == null || secretBytes.length == 0) {
            throw new IllegalArgumentException("Secret bytes must not be empty");
        }
        byte[] counter = ByteBuffer.allocate(8).putLong(timeStep).array();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % DIGITS_POWER;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA1 is unavailable", exception);
        }
    }

    /**
     * Verifies a 6-digit TOTP code within {@code ±windowSteps} around {@code epochMillis}.
     */
    public static boolean verifyCode(String base32Secret, String rawCode, long epochMillis, int windowSteps) {
        return verifyAndGetStep(base32Secret, rawCode, epochMillis, windowSteps, -1L).isPresent();
    }

    /**
     * Verifies a 6-digit TOTP code and returns the matched 30-second time step,
     * rejecting any step that has already been used ({@code <= lastUsedStep}).
     */
    public static OptionalLong verifyAndGetStep(
            String base32Secret,
            String rawCode,
            long epochMillis,
            int windowSteps,
            long lastUsedStep
    ) {
        if (base32Secret == null || base32Secret.isBlank() || !isValidCodeFormat(rawCode)) {
            return OptionalLong.empty();
        }
        byte[] secretBytes;
        try {
            secretBytes = decodeBase32(base32Secret);
            if (secretBytes.length == 0) return OptionalLong.empty();
        } catch (IllegalArgumentException ignored) {
            return OptionalLong.empty();
        }

        String normalizedCode = normalizeCode(rawCode);
        byte[] expectedBytes = normalizedCode.getBytes(StandardCharsets.US_ASCII);
        long currentStep = getTimeStep(epochMillis);
        int clampedWindow = Math.clamp(windowSteps, 0, 10);

        if (matchesStep(secretBytes, expectedBytes, currentStep, lastUsedStep)) {
            return OptionalLong.of(currentStep);
        }
        for (int delta = 1; delta <= clampedWindow; delta++) {
            long pastStep = currentStep - delta;
            if (pastStep >= 0 && matchesStep(secretBytes, expectedBytes, pastStep, lastUsedStep)) {
                return OptionalLong.of(pastStep);
            }
            long futureStep = currentStep + delta;
            if (matchesStep(secretBytes, expectedBytes, futureStep, lastUsedStep)) {
                return OptionalLong.of(futureStep);
            }
        }
        return OptionalLong.empty();
    }

    private static boolean matchesStep(byte[] secretBytes, byte[] expectedCodeBytes, long step, long lastUsedStep) {
        if (lastUsedStep >= 0 && step <= lastUsedStep) {
            return false;
        }
        String candidate = generateCodeAtStep(secretBytes, step);
        return MessageDigest.isEqual(expectedCodeBytes, candidate.getBytes(StandardCharsets.US_ASCII));
    }

    public static long getTimeStep(long epochMillis) {
        return Math.floorDiv(epochMillis, TIME_STEP_MILLIS);
    }

    /** Encodes raw bytes into unpadded uppercase RFC 4648 Base32. */
    public static String encodeBase32(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder result = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = data[0] & 0xFF;
        int next = 1;
        int bitsLeft = 8;
        while (bitsLeft > 0 || next < data.length) {
            if (bitsLeft < 5) {
                if (next < data.length) {
                    buffer <<= 8;
                    buffer |= (data[next++] & 0xFF);
                    bitsLeft += 8;
                } else {
                    int pad = 5 - bitsLeft;
                    buffer <<= pad;
                    bitsLeft += pad;
                }
            }
            int index = (buffer >> (bitsLeft - 5)) & 0x1F;
            bitsLeft -= 5;
            result.append(BASE32_ALPHABET.charAt(index));
        }
        return result.toString();
    }

    /** Decodes an RFC 4648 Base32 string (case-insensitive, ignores spaces, hyphens, and '=' padding). */
    public static byte[] decodeBase32(String base32) {
        String clean = cleanBase32(base32);
        if (clean.isEmpty()) return new byte[0];

        ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() * 5 / 8);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int val = charToBase32Value(c);
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static String cleanBase32(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '-' || c == '=' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static int charToBase32Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= '2' && c <= '7') return 26 + (c - '2');
        return -1;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
