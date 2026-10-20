package ru.cherdakmd.chrdktierforge;

import java.util.Random;

/**
 * Идентификаторы модификаторов атрибутов на тир-предметах.
 *
 * <p>Minecraft склеивает модификаторы с одинаковым id: если на четырёх деталях
 * брони стоит {@code chrdk:tier_movespeed}, применяется только один — остальные
 * молча игнорируются. Именно поэтому до 1.4.124 полный сет не давал скорости, а
 * бонусы брони и ковки не складывались между деталями. Теперь у каждого
 * предмета свой суффикс ({@code tier_movespeed_3f9a1c0b7d2e}), который живёт в
 * PDC и не меняется при пересборке.</p>
 *
 * <p>Класс чистый — генерация и разбор ключей проверяются юнит-тестами.</p>
 */
final class TierModifierIds {

    /**
     * Версия раскладки модификаторов. Старые предметы без отпечатка пересобираются
     * один раз при входе/смене слота (см. {@link TierItems#refreshTierModifiers}).
     */
    static final String STAMP = "1";

    static final String TIER_PREFIX = "tier_";
    static final String UPGRADE_PREFIX = "fu_";
    static final String PROPERTY_PREFIX = "pr_";

    /** 12 hex-символов ≈ 48 бит: коллизия между предметами практически невозможна. */
    static final int UID_LENGTH = 12;

    private TierModifierIds() {
    }

    /** Случайный суффикс из нижнего hex-регистра. */
    static String newUid(Random random) {
        StringBuilder builder = new StringBuilder(UID_LENGTH);
        for (int index = 0; index < UID_LENGTH; index++) {
            builder.append(Character.forDigit(random.nextInt(16), 16));
        }
        return builder.toString();
    }

    /**
     * Ключ модификатора: {@code tag + "_" + uid}. Без uid возвращается старый
     * ключ — так распознаются предметы версий до 1.4.124.
     */
    static String key(String tag, String uid) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        return uid == null || uid.isBlank() ? tag : tag + "_" + uid;
    }

    /** Наш ли ключ: тир, ковка или свойство тира (включая старые ключи без uid). */
    static boolean ours(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return key.startsWith(TIER_PREFIX) || key.startsWith(UPGRADE_PREFIX)
                || key.startsWith(PROPERTY_PREFIX);
    }

    /** Похож ли суффикс на сгенерированный нами (для чтения PDC). */
    static boolean validUid(String uid) {
        if (uid == null || uid.length() != UID_LENGTH) {
            return false;
        }
        for (int index = 0; index < uid.length(); index++) {
            char symbol = uid.charAt(index);
            if (!((symbol >= '0' && symbol <= '9') || (symbol >= 'a' && symbol <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
