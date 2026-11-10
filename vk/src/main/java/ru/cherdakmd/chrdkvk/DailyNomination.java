package ru.cherdakmd.chrdkvk;

/**
 * Победитель одной номинации суток. {@code value} — основная величина,
 * {@code secondary} — уточнение (например, алмазы при подсчёте блоков).
 * Ники приходят из Bukkit и режутся по длине, UUID/IP/суммы платежей не несутся.
 */
public record DailyNomination(DailyCardKind kind, String playerName, long value, long secondary) {

    public static final int MAX_NICK_LENGTH = 24;

    public DailyNomination {
        playerName = safeNick(playerName);
        if (value < 0) value = 0;
        if (secondary < 0) secondary = 0;
    }

    /** Оставляем буквы/цифры/подчёркивание/дефис, режем длину, без управления. */
    public static String safeNick(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < MAX_NICK_LENGTH; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    public boolean valid() {
        return !playerName.isBlank();
    }

    /** Строка величины на карточке и в тексте записи. */
    public String valueLine(boolean showValues) {
        if (!showValues) {
            return "";
        }
        return switch (kind) {
            case RICHEST -> value + " алмазов на счету";
            case PRESTIGE -> value + " " + plural(value, "ступень", "ступени", "ступеней") + " престижа";
            case MINER -> value + " " + plural(value, "блок", "блока", "блоков")
                    + " · " + secondary + " " + plural(secondary, "алмаз", "алмаза", "алмазов");
            case HUNTER -> value + " " + plural(value, "победа", "победы", "побед") + " над монстрами";
        };
    }

    private static String plural(long n, String one, String few, String many) {
        long lastTwo = n % 100;
        long last = n % 10;
        if (lastTwo >= 11 && lastTwo <= 14) return many;
        if (last == 1) return one;
        if (last >= 2 && last <= 4) return few;
        return many;
    }
}
