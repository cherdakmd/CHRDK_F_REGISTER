package ru.cherdakmd.chrdkguilds;

import java.util.UUID;

/** Доказанный инцидент: один источник не становится новым нарушением при каждом тике. */
record GuildViolation(UUID id, long at, UUID victim, UUID source, String kind,
                      boolean peaceful, boolean forgiven) {
    GuildViolation {
        if (id == null || victim == null || source == null || at < 0 || kind == null
                || !java.util.Set.of("melee", "projectile", "potion", "explosion", "fangs").contains(kind))
            throw new IllegalArgumentException("violation");
    }
    GuildViolation forgive() { return new GuildViolation(id,at,victim,source,kind,peaceful,true); }
}
