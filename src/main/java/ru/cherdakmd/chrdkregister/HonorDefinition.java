package ru.cherdakmd.chrdkregister;

import java.util.Locale;

/**
 * Определение особого звания — «Старый цитрус» и ему подобные.
 *
 * <p>Звание не является купленным рангом: оно живёт в отдельном
 * {@code honors.json}, не меняет {@code data.json}, сроки и историю платежей.
 * Но у него есть {@code grantLevel} — ступень, которую звание добавляет к
 * эффективному уровню привилегий: {@code max(оплаченный ранг, все звания)}.
 * Так владелец может вручную отметить многолетнего игрока, не подделывая
 * покупку и не выдавая «бесплатный донат» через магазин.</p>
 *
 * <p>{@code format} — MiniMessage-шаблон ника с {@code %name%}. Для первого
 * звания это градиент от лаймового к бирюзовому. {@code listed} скрывает
 * служебные звания из публичного реестра, но не из чата.</p>
 */
public record HonorDefinition(String id, String name, String format, String badge,
                              int grantLevel, String description, String joinLine,
                              boolean listed) {

    /** Верхняя ступень лестницы ядра; выше привязать нельзя. */
    public static final int MAX_GRANT_LEVEL = 5;
    public static final String DEFAULT_FORMAT = "%name%";

    public HonorDefinition {
        id = normalizeId(id);
        name = text(name, id);
        format = format == null || format.isBlank() ? DEFAULT_FORMAT : format.trim();
        if (!format.contains("%name%")) {
            format = format + "%name%";
        }
        badge = badge == null ? "" : badge.trim();
        grantLevel = Math.max(0, Math.min(MAX_GRANT_LEVEL, grantLevel));
        description = description == null ? "" : description.trim();
        joinLine = joinLine == null ? "" : joinLine.trim();
    }

    /** Машинный id: только латиница, цифры, дефис и подчёркивание. */
    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return value;
    }

    public static boolean validId(String raw) {
        String value = normalizeId(raw);
        return !value.isEmpty() && value.length() <= 32;
    }

    /** Образец «Старый цитрус», создаваемый при первом запуске без игрока. */
    public static HonorDefinition oldCitrus() {
        return new HonorDefinition("old-citrus", "Старый цитрус",
                "<gradient:#32CD32:#40E0D0>%name%</gradient>", "🍋", 5,
                "За выслугу лет и многолетнее знакомство с сервером", "", true);
    }

    /** Имя игрока в оформлении звания. */
    public String decorate(String playerName) {
        if (playerName == null || playerName.isBlank()) return "";
        return format.replace("%name%", playerName);
    }

    /** Значение поля для редактора и команд. */
    public String field(String key) {
        return switch (key) {
            case "id" -> id;
            case "name" -> name;
            case "format", "gradient" -> format;
            case "badge" -> badge;
            case "level", "grant-level" -> Integer.toString(grantLevel);
            case "description" -> description;
            case "join", "join-line" -> joinLine;
            case "listed", "visible" -> Boolean.toString(listed);
            default -> "";
        };
    }

    /** Изменить одно поле; неизвестное поле или мусор оставляет звание прежним. */
    public HonorDefinition with(String key, String raw) {
        String value = raw == null ? "" : raw.trim();
        return switch (key) {
            case "name" -> value.isEmpty() ? this
                    : new HonorDefinition(id, value, format, badge, grantLevel, description, joinLine, listed);
            case "format", "gradient" -> value.isEmpty() ? this
                    : new HonorDefinition(id, name, value, badge, grantLevel, description, joinLine, listed);
            case "badge" -> new HonorDefinition(id, name, format, value, grantLevel, description, joinLine, listed);
            case "level", "grant-level" -> {
                int parsed = parseLevel(value);
                yield parsed < 0 ? this
                        : new HonorDefinition(id, name, format, badge, parsed, description, joinLine, listed);
            }
            case "description" ->
                    new HonorDefinition(id, name, format, badge, grantLevel, value, joinLine, listed);
            case "join", "join-line" ->
                    new HonorDefinition(id, name, format, badge, grantLevel, description, value, listed);
            case "listed", "visible" -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) yield this;
                yield new HonorDefinition(id, name, format, badge, grantLevel, description, joinLine,
                        Boolean.parseBoolean(value));
            }
            default -> this;
        };
    }

    /** -1 — не число или вне 0..5; редактор покажет ошибку, а не молча обнулит. */
    public static int parseLevel(String raw) {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            return value < 0 || value > MAX_GRANT_LEVEL ? -1 : value;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String text(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }
}
