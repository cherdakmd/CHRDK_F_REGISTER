package ru.cherdakmd.chrdkfishing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated single-use bait identity. Ledger redemption is required to prevent copied items. */
public final class BaitToken {
    public record Token(UUID id, String kind, String mac) {
        public Token {
            Objects.requireNonNull(id);
            if (BaitCatalog.byId(kind) == null || mac == null || !mac.matches("[A-Za-z0-9_-]{43}"))
                throw new IllegalArgumentException("bait token");
        }
    }
    public static Token issue(byte[] key, UUID id, String kind) {
        check(key); Objects.requireNonNull(id);
        if (BaitCatalog.byId(kind) == null) throw new IllegalArgumentException("unknown bait");
        return new Token(id, kind, encode(sign(key, id, kind)));
    }
    public static boolean verify(byte[] key, Token token) {
        check(key); if (token == null) return false;
        try {
            byte[] actual = Base64.getUrlDecoder().decode(token.mac());
            return actual.length == 32 && encode(actual).equals(token.mac())
                    && MessageDigest.isEqual(actual, sign(key, token.id(), token.kind()));
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static void check(byte[] key) {
        if (key == null || key.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("bait key");
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] sign(byte[] key, UUID id, String kind) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(("CHRDK-BAIT-V1\n" + id + "\n" + kind).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) { throw new IllegalStateException("bait HMAC unavailable", error); }
    }
    private BaitToken() { }
}
