package ru.cherdakmd.chrdkregister;

import java.util.function.Supplier;

/**
 * Brute-force protection for password and 2FA entry.
 *
 * <p>Counters live in {@link RegistrationStore}, so they survive reconnects and server restarts, and a
 * password can no longer be brute-forced by simply rejoining. Two independent limits are enforced: per
 * account and per connecting IP (the latter also covers attacks that walk through many nicknames).</p>
 */
public final class AuthThrottle {
    /** Outcome of a throttle check. */
    public enum Decision {
        /** The attempt may be processed right away. */
        ALLOW,
        /** The attempt is allowed after a delay — used as an exponential back-off. */
        DELAY,
        /** The attempt must be rejected: account or IP is temporarily blocked. */
        BLOCK
    }

    /** Throttle verdict returned to the caller. */
    public record Result(Decision decision, int delaySeconds, long blockedUntil, boolean ipBan, String reason) {
        public boolean blocked() {
            return decision == Decision.BLOCK;
        }

        public static Result allow() {
            return new Result(Decision.ALLOW, 0, 0, false, "");
        }
    }

    private final RegistrationStore store;
    private final Supplier<PluginConfig> configSupplier;

    public AuthThrottle(RegistrationStore store, Supplier<PluginConfig> configSupplier) {
        this.store = store;
        this.configSupplier = configSupplier;
    }

    /** Checks the current state before verifying a password or a 2FA code. */
    public Result check(String playerName, String ipAddress, long nowMillis) {
        PluginConfig config = configSupplier.get();
        RegistrationStore.AttemptRecord account = store.getAttempt(playerName, RegistrationStore.AttemptKind.ACCOUNT)
                .orElse(null);
        if (account != null && account.blockedUntil > nowMillis) {
            return new Result(Decision.BLOCK, 0, account.blockedUntil, false,
                    "аккаунт временно заблокирован после нескольких неудачных попыток");
        }
        if (ipAddress != null && !ipAddress.isBlank()) {
            RegistrationStore.AttemptRecord ip = store.getAttempt(ipAddress, RegistrationStore.AttemptKind.IP)
                    .orElse(null);
            if (ip != null && ip.blockedUntil > nowMillis) {
                return new Result(Decision.BLOCK, 0, ip.blockedUntil, true,
                        "слишком много неудачных попыток с этого IP");
            }
        }
        int failures = account == null ? 0 : account.count;
        int delay = config.backoffForAttempt(failures + 1);
        if (failures > 0 && delay > 0) {
            return new Result(Decision.DELAY, delay, 0, false, "задержка после неудачных попыток");
        }
        return Result.allow();
    }

    /** Records one failed attempt and returns the resulting verdict for the player. */
    public Result registerFailure(String playerName, String ipAddress, long nowMillis) {
        PluginConfig config = configSupplier.get();
        Result accountResult = bump(RegistrationStore.AttemptKind.ACCOUNT, playerName, ipAddress, nowMillis,
                config.accountMaxAttempts(), config.accountWindowMinutes(), config.accountBlockMinutes(),
                "аккаунт временно заблокирован после нескольких неудачных попыток");
        if (ipAddress == null || ipAddress.isBlank()) {
            return accountResult;
        }
        Result ipResult = bump(RegistrationStore.AttemptKind.IP, ipAddress, playerName, nowMillis,
                config.perIpMaxAttempts(), config.perIpWindowMinutes(), config.tempIpBanMinutes(),
                "слишком много неудачных попыток с этого IP");
        if (accountResult.blocked()) return accountResult;
        if (ipResult.blocked()) return ipResult;
        if (ipResult.decision() == Decision.DELAY) return ipResult;
        return accountResult;
    }

    /** Clears counters after a successful password or 2FA verification. */
    public void registerSuccess(String playerName, String ipAddress) {
        store.removeAttempt(playerName, RegistrationStore.AttemptKind.ACCOUNT);
        if (ipAddress != null && !ipAddress.isBlank()) {
            RegistrationStore.AttemptRecord ip = store
                    .getAttempt(ipAddress, RegistrationStore.AttemptKind.IP)
                    .orElse(null);
            if (ip != null) {
                // Keep the historical failure counter but reset the active window and block.
                ip.count = 0;
                ip.windowStart = System.currentTimeMillis();
                ip.blockedUntil = 0;
                store.saveAttempt(ipAddress, RegistrationStore.AttemptKind.IP, ip);
            }
        }
    }

    /** Returns the total number of recorded failures for a player (used for audit entries). */
    public int totalFailures(String playerName) {
        return store.getAttempt(playerName, RegistrationStore.AttemptKind.ACCOUNT)
                .map(record -> record.totalFailures)
                .orElse(0);
    }

    private Result bump(
            RegistrationStore.AttemptKind kind,
            String key,
            String peer,
            long nowMillis,
            int maxAttempts,
            int windowMinutes,
            int blockMinutes,
            String blockReason
    ) {
        PluginConfig config = configSupplier.get();
        RegistrationStore.AttemptRecord record = store.getAttempt(key, kind)
                .orElseGet(RegistrationStore.AttemptRecord::new);
        long windowMillis = windowMinutes * 60_000L;
        if (record.windowStart <= 0 || nowMillis - record.windowStart > windowMillis) {
            record.count = 0;
            record.windowStart = nowMillis;
        }
        if (record.blockedUntil > 0 && record.blockedUntil <= nowMillis) {
            record.blockedUntil = 0;
        }
        record.count++;
        record.totalFailures++;
        if (peer != null && !peer.isBlank()) {
            record.lastIp = peer;
        }
        if (blockMinutes > 0 && record.count >= maxAttempts) {
            record.blockedUntil = nowMillis + blockMinutes * 60_000L;
            store.saveAttempt(key, kind, record);
            return new Result(Decision.BLOCK, 0, record.blockedUntil,
                    kind == RegistrationStore.AttemptKind.IP, blockReason);
        }
        store.saveAttempt(key, kind, record);
        int delay = config.backoffForAttempt(record.count + 1);
        return delay > 0
                ? new Result(Decision.DELAY, delay, 0, false, "задержка после неудачных попыток")
                : Result.allow();
    }
}
