package ru.cherdakmd.chrdkartifacts;

/**
 * Фолбэки текстов артефактов. Имена и описания живут в `messages.yml`
 * (`art.<id>.name|lore`); если администратор удалил секцию или перевёл файл
 * не полностью, {@link ru.cherdakmd.chrdkregister.Messages#raw} возвращает
 * пустую строку. Пустое имя хуже всего: предмет выглядит безымянным, а в чате
 * остаётся «Судьба одарила вас артефактом: !». Поэтому текст собирается через
 * этот класс: живая строка → иначе читаемое имя из ида.
 *
 * <p>Класс чистый (никаких Bukkit-типов), чтобы проверяться юнит-тестами.</p>
 */
public final class ArtifactText {

    private ArtifactText() {
    }

    /** Имя артефакта: строка из messages.yml, а при её отсутствии — имя из ида. */
    public static String orFallback(String value, String id) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return humanize(id);
    }

    /** Описание: пустая строка означает «строку лора не показывать». */
    public static String loreOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * «koren-zhizni» → «Koren Zhizni». Латиница (ид — ASCII) — осознанный
     * компромисс: это аварийный текст, он виден только при поломанном
     * messages.yml, но предмет и сообщение остаются читаемыми.
     */
    public static String humanize(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(id.length());
        for (String part : id.trim().split("[-_\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
