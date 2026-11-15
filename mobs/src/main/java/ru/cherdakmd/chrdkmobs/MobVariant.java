package ru.cherdakmd.chrdkmobs;

import java.util.List;
import java.util.Locale;

/**
 * Редкие особые мобы любого тира: своя тема способностей, своё имя
 * и тематический лут. Вариант выпадает вместо элиты (шанс из конфига),
 * мощность считается поверх тира и прогресса как у элиты.
 */
enum MobVariant {

    /** Проклятые: тьма, тяга к жизни, проклятая сущность в луте. */
    CURSED,
    /** Тлеющие: огонь и жар, уголёк тления в луте. */
    SMOLDERING,
    /** Всрaтые: ядовито-гадостные и уродливо-странные, жижа в луте. */
    GROSS,
    /** Кринжовые: бессмысленный хаос приёмов, кринж-фрагмент в луте. */
    CRINGE;

    /** Идентификатор для PDC и конфига. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Ключ имени варианта в messages.yml (с разметкой). */
    public String titleKey() {
        return "variant-" + id();
    }

    /** Ид тематического лута варианта (MobLootItems). */
    public String lootId() {
        return "variant-" + id();
    }

    /** Тематический пул способностей: набор берётся только из него. */
    public List<EliteAbilities> pool() {
        return switch (this) {
            case CURSED -> List.of(EliteAbilities.RESISTANCE, EliteAbilities.STRENGTH,
                    EliteAbilities.WEAKNESS_TOUCH, EliteAbilities.LIFESTEAL, EliteAbilities.THORNS);
            case SMOLDERING -> List.of(EliteAbilities.FIRE_RESISTANCE, EliteAbilities.FIRE_TOUCH,
                    EliteAbilities.STRENGTH, EliteAbilities.KNOCKBACK);
            case GROSS -> List.of(EliteAbilities.VENOM, EliteAbilities.SLOW_TOUCH,
                    EliteAbilities.MINIONS, EliteAbilities.REGENERATION);
            case CRINGE -> List.of(EliteAbilities.BLINK, EliteAbilities.MINIONS,
                    EliteAbilities.SPEED, EliteAbilities.KNOCKBACK, EliteAbilities.THORNS);
        };
    }

    /** Обратный поиск по идентификатору; неизвестный — {@code null}. */
    public static MobVariant byId(String id) {
        if (id == null) {
            return null;
        }
        for (MobVariant variant : values()) {
            if (variant.id().equals(id)) {
                return variant;
            }
        }
        return null;
    }
}
