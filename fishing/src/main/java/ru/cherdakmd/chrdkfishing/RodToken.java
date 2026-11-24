package ru.cherdakmd.chrdkfishing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated rod attributes, not a purchase/upgrade journal or anti-copy ledger. */
public final class RodToken {
    private static final String DOMAIN = "CHRDK-ROD-V1";
    public record Token(UUID id, int level, String mac) {
        public Token {
            Objects.requireNonNull(id, "rod id");
            FishingModel.rod(level);
            if (mac == null || !mac.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("rod signature");
        }
    }
    private RodToken() { }
    public static Token issue(byte[] key, UUID id, int level) {
        checkKey(key); Objects.requireNonNull(id, "rod id"); FishingModel.rod(level);
        return new Token(id, level, encode(sign(key, id, level)));
    }
    public static boolean verify(byte[] key, Token token) {
        checkKey(key);
        if (token == null) return false;
        try {
            byte[] actual = Base64.getUrlDecoder().decode(token.mac());
            return actual.length == 32 && encode(actual).equals(token.mac())
                    && MessageDigest.isEqual(actual, sign(key, token.id(), token.level()));
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static void checkKey(byte[] key) {
        if (key == null || key.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("rod key");
    }
    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] sign(byte[] key, UUID id, int level) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal((DOMAIN + "\n" + id + "\n" + level).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) { throw new IllegalStateException("rod HMAC unavailable", error); }
    }
}
