package ru.cherdakmd.chrdksocial;

/**
 * Чистая подготовка текста события для беседы: без Bukkit, сети и разметки.
 *
 * <p>В сообщество уходит только ник и нейтральная формулировка. Координаты,
 * IP, UUID, причина смерти, убийца и содержимое инвентаря не публикуются:
 * их достаточно в игровом чате и в логах сервера.</p>
 */
public final class SocialEventText {

    private static final int MAX_NAME_LENGTH = 16;

    private SocialEventText() {
    }

    /** Разрешаем только безопасные символы Bukkit-ника. */
    public static String safePlayer(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < MAX_NAME_LENGTH; i++) {
            char c = raw.charAt(i);
            if ((Character.isLetterOrDigit(c) && c < 128) || c == '_') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Обрезка по лимиту сообщения; пустой текст в беседу не уходит. */
    public static String limit(String text, int maxLength) {
        if (text == null || text.isBlank()) return "";
        String flat = text.trim();
        if (maxLength <= 0 || flat.length() <= maxLength) return flat;
        return flat.substring(0, Math.max(1, maxLength - 1)) + "…";
    }
}
