package ru.cherdakmd.chrdkclasses;

import java.util.UUID;

/** Одно конкретное состояние пересборки. Одноразовое потребление хранит GUI-сеанс. */
record ClassRespecQuote(UUID owner, LegacyClassBasis basis, long expiresAt, long cooldownSeconds) {
    static final long LIFETIME_MILLIS = 30_000;

    ClassRespecQuote {
        if (owner == null || basis == null || !owner.equals(basis.playerId()) || basis.deficit() > 0
                || cooldownSeconds < 0 || cooldownSeconds > 720L * 3_600L
                || expiresAt != Math.addExact(basis.capturedAt(), LIFETIME_MILLIS)) {
            throw new IllegalArgumentException("Некорректный предварительный расчёт пересборки");
        }
    }

    static ClassRespecQuote create(LegacyClassBasis basis, long cooldownSeconds) {
        return new ClassRespecQuote(basis.playerId(), basis,
                Math.addExact(basis.capturedAt(), LIFETIME_MILLIS), cooldownSeconds);
    }

    boolean matches(UUID player, LegacyClassBasis current, long now, long currentCooldownSeconds) {
        return owner.equals(player) && cooldownSeconds == currentCooldownSeconds
                && now >= basis.capturedAt() && now < expiresAt
                && basis.sameState(current);
    }
}
