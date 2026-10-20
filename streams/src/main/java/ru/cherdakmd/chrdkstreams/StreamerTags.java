package ru.cherdakmd.chrdkstreams;

/**
 * Сборка подписи стримера: жирный ник плюс метка «[СТРИМ]».
 *
 * <p>Класс чистый — только строки MiniMessage, никакого Bukkit. Благодаря этому
 * подпись проверяется юнит-тестами: жирным становится именно ник, а не префикс
 * каталога и не метка, и ник с разметкой не ломает строку.</p>
 */
final class StreamerTags {

    private StreamerTags() {
    }

    /**
     * Ник с меткой: {@code <bold>Ник</bold> <red>[СТРИМ]</red>}.
     *
     * @param decoratedName имя, уже оформленное ядром (может содержать тег каталога)
     * @param playerName    ник без разметки — его и делаем жирным
     * @param label         текст метки
     * @param color         цвет метки (имя цвета MiniMessage)
     */
    static String markup(String decoratedName, String playerName, String label, String color) {
        String base = decoratedName == null ? "" : decoratedName;
        String safeColor = color == null || color.isBlank() ? "red" : color;
        String tag = "<" + safeColor + ">[" + safeLabel(label) + "]</" + safeColor + ">";
        return boldName(base, playerName) + " " + tag;
    }

    /** Жирный только ник: тег каталога перед ником остаётся как был. */
    static String boldName(String decoratedName, String playerName) {
        String base = decoratedName == null ? "" : decoratedName;
        String name = playerName == null ? "" : playerName.trim();
        if (name.isEmpty()) {
            return "<bold>" + base + "</bold>";
        }
        int at = base.lastIndexOf(name);
        if (at < 0) {
            return "<bold>" + base + "</bold>";
        }
        return base.substring(0, at) + "<bold>" + name + "</bold>"
                + base.substring(at + name.length());
    }

    /** Текст метки: без разметки, не пустой, не длиннее 16 символов. */
    static String safeLabel(String label) {
        String value = label == null ? "" : label.replaceAll("<[^>]*>", "")
                .replace("<", "").replace(">", "").trim();
        if (value.isEmpty()) {
            value = "СТРИМ";
        }
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    /** Цвет для текущей фазы мигания. */
    static String blinkColor(boolean phase, String colorA, String colorB) {
        return phase ? (colorA == null || colorA.isBlank() ? "red" : colorA)
                : (colorB == null || colorB.isBlank() ? "dark_gray" : colorB);
    }
}
