package ru.cherdakmd.chrdkguilds;

import java.util.Locale;

/**
 * Правила гильдий в одном месте (1.4.163).
 *
 * <p>Цена уровня, потолок состава и вес боя — это то, что игрок видит в меню и
 * в сообщениях, а сервис проверяет при действии. Держать два набора чисел
 * нельзя: «в меню 128, а списалось 256» — худший вид бага. Здесь только чистая
 * математика, поэтому всё проверяется тестами без сервера.</p>
 */
final class GuildMath {

    /** Потолок уровня гильдии. */
    static final int MAX_LEVEL = 5;
    /** Потолок состава — даже на пятом уровне гильдия не становится армией. */
    static final int MAX_MEMBERS = 26;
    /** Сколько очков славы даёт победа в войне. */
    static final int GLORY_PER_WIN = 50;
    /** Сколько славы даёт поражение — участие тоже считается. */
    static final int GLORY_PER_LOSS = 10;

    /** Цена перехода с уровня {@code level} на следующий. */
    static int upgradeCost(int level) {
        return switch (Math.clamp(level, 1, MAX_LEVEL)) {
            case 1 -> 64;
            case 2 -> 192;
            case 3 -> 384;
            case 4 -> 768;
            default -> 0;
        };
    }

    /** Потолок состава для уровня. */
    static int memberCap(int level) {
        return switch (Math.clamp(level, 1, MAX_LEVEL)) {
            case 1 -> 10;
            case 2 -> 14;
            case 3 -> 18;
            case 4 -> 22;
            default -> MAX_MEMBERS;
        };
    }

    /** Можно ли расти дальше. */
    static boolean canUpgrade(int level) {
        return level < MAX_LEVEL;
    }

    /**
     * Очки за убийство в войне.
     *
     * <p>Бой на приватах — любой, не только на своём — стоит вдвое: там нельзя
     * «случайно» оказаться, и защита территории становится частью войны.</p>
     */
    static int killPoints(boolean claimed) {
        return claimed ? 2 : 1;
    }

    /** Окно защиты от накрутки: одна и та же пара очков не даёт чаще раза в окно. */
    static boolean countsKill(long lastKillAt, long now, long windowMillis) {
        if (lastKillAt <= 0 || windowMillis <= 0) {
            return true;
        }
        return now - lastKillAt >= windowMillis;
    }

    /** Римская цифра уровня — для меню и текстов. */
    static String roman(int level) {
        return switch (Math.clamp(level, 1, MAX_LEVEL)) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    /** Алмазы в текст: без дробей, с разделителем разрядов через пробел. */
    static String diamonds(long amount) {
        long safe = Math.max(0L, amount);
        String digits = Long.toString(safe);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < digits.length(); index++) {
            if (index > 0 && (digits.length() - index) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(index));
        }
        return out.toString();
    }

    /** Время до конца войны в виде «1 ч 5 мин» или «12 мин». */
    static String clock(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3_600;
        long minutes = (safe % 3_600) / 60;
        if (hours > 0) {
            return hours + " ч " + minutes + " мин";
        }
        long rest = safe % 60;
        return minutes > 0 ? minutes + " мин" : rest + " с";
    }

    /** Сравнение тегов и названий без учёта регистра — для уникальности. */
    static String key(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private GuildMath() {
    }
}
