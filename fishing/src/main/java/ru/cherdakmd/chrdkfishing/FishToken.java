package ru.cherdakmd.chrdkfishing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated data for a fish item. This is NOT a transaction ledger: a copied
 * valid token remains valid and MUST be spent at most once by a durable journal.
 * The plugin must persist its secret safely before using tokens, and must not
 * generate a new secret when an existing key is missing or unreadable.
 */
public final class FishToken {
    private static final String DOMAIN = "CHRDK-FISH-V1";
    public static final int KEY_BYTES = 32;
    public static final long MAX_PRICE_POINTS = 1_000_000;
    public record Token(UUID id, FishingModel.Catch fish, long quotedPrice, String mac) {
        public Token {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(fish, "fish");
            if (quotedPrice < 1 || quotedPrice > MAX_PRICE_POINTS || mac == null || mac.length() != 43
                    || !mac.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("fish token");
        }
    }
    private FishToken() { }

    public static Token issue(byte[] key, UUID id, FishingModel.Catch fish, long quotedPrice) {
        validateKey(key);
        FishingModel.validate(fish);
        if (id == null || quotedPrice < 1 || quotedPrice > MAX_PRICE_POINTS) throw new IllegalArgumentException("fish issue");
        return new Token(id, fish, quotedPrice, encode(sign(key, payload(id, fish, quotedPrice))));
    }
    public static boolean verify(byte[] key, Token token) {
        validateKey(key);
        if (token == null) return false;
        try {
            FishingModel.validate(token.fish());
            byte[] actual = Base64.getUrlDecoder().decode(token.mac());
            return actual.length == 32 && encode(actual).equals(token.mac())
                    && MessageDigest.isEqual(actual, sign(key, payload(token.id(), token.fish(), token.quotedPrice())));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
    private static byte[] payload(UUID id, FishingModel.Catch fish, long price) {
        return (DOMAIN + "\n" + id + "\n" + fish.speciesId() + "\n" + fish.size().name()
                + "\n" + fish.lengthCm() + "\n" + price).getBytes(StandardCharsets.UTF_8);
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static void validateKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) throw new IllegalArgumentException("fish key");
    }
    private static byte[] sign(byte[] key, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", error);
        }
    }
}
