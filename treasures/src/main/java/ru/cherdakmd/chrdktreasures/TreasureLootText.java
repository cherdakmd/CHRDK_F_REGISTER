package ru.cherdakmd.chrdktreasures;

import org.bukkit.Material;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «Что именно выпало»: список добычи клада для чата и журнала сервера.
 *
 * <p>Сервер не знает языка клиента, поэтому имя обычного предмета берётся не
 * из материала, а тегом {@code <lang:item.minecraft.diamond>}: MiniMessage
 * развернёт его в название на языке игрока. Именные находки («Кровавый суп»)
 * показываются своим названием — они и так написаны по-русски.</p>
 *
 * <p>Класс намеренно не знает про ядро и {@code Messages}: он остаётся чистой
 * логикой и проверяется юнит-тестами без сервера. Строка для чата собирается
 * в {@link TreasureLootMarkup}.</p>
 *
 * <p>Список обрезается: в одной строке чата не должно быть двадцати предметов.
 * Сколько показывать, решает {@code feedback.loot-details-max}; остальное
 * сворачивается в «и ещё N». Логика чистая и покрыта тестами — Bukkit нужен
 * только для проверки «блок это или предмет», и при отсутствии сервера
 * (юнит-тесты) она мягко откатывается к {@code item.minecraft.*}.</p>
 */
public final class TreasureLootText {

    /** Сколько стеков показывать по умолчанию. */
    public static final int DEFAULT_LIMIT = 6;
    /** Верхняя граница показа: больше в одну строку не влезает. */
    public static final int MAX_LIMIT = 12;

    /** Один предмет в списке: имя для чата и имя без разметки для лога. */
    public record Part(String markup, String plain, int amount) {

        public Part {
            markup = markup == null ? "" : markup;
            plain = plain == null ? "" : plain;
        }
    }

    /** Готовый список: показанная часть и сколько позиций свернулось. */
    public record Summary(List<Part> parts, int hidden) {

        public Summary {
            parts = List.copyOf(parts == null ? List.of() : parts);
        }

        public boolean isEmpty() {
            return parts.isEmpty();
        }

        /**
         * Строка для консоли сервера: без разметки и тегов языка,
         * {@code DIAMOND ×3, GOLD_INGOT ×2 (+1)}.
         */
        public String plain() {
            StringBuilder out = new StringBuilder();
            for (Part part : parts) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(part.plain()).append(" ×").append(part.amount());
            }
            if (hidden > 0) {
                out.append(" (+").append(hidden).append(')');
            }
            return out.toString();
        }
    }

    private TreasureLootText() {
    }

    /**
     * Экранирует разметку в имени находки: имя приходит из {@code loot.yml},
     * и «<» в нём не должен превращать текст в MiniMessage-тег.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('<') < 0 && value.indexOf('\\') < 0) {
            return value;
        }
        return value.replace("\\", "\\\\").replace("<", "\\<");
    }

    /**
     * Готовит список к показу.
     *
     * @param stacks добыча клада в порядке броска
     * @param limit  сколько позиций показывать (1..{@link #MAX_LIMIT})
     */
    public static Summary summarize(List<TreasureLoot.LootStack> stacks, int limit) {
        if (stacks == null || stacks.isEmpty()) {
            return new Summary(List.of(), 0);
        }
        List<TreasureLoot.LootStack> clean = new ArrayList<>(stacks.size());
        for (TreasureLoot.LootStack stack : stacks) {
            if (stack != null && stack.amount() > 0 && stack.material() != null) {
                clean.add(stack);
            }
        }
        int max = Math.max(1, Math.min(MAX_LIMIT, limit));
        int shown = Math.min(max, clean.size());
        List<Part> parts = new ArrayList<>(shown);
        for (int index = 0; index < shown; index++) {
            parts.add(part(clean.get(index)));
        }
        return new Summary(parts, clean.size() - shown);
    }

    /**
     * Одна позиция списка.
     *
     * <p>Именная находка получает своё название (экранированное: имя приходит
     * из конфига и не должно вставлять разметку), обычная — тег языка клиента,
     * а для лога — материал как есть.</p>
     */
    public static Part part(TreasureLoot.LootStack stack) {
        if (stack == null) {
            return new Part("", "", 0);
        }
        String name = stack.name();
        if (name != null && !name.isBlank()) {
            return new Part(escape(name), name, stack.amount());
        }
        return new Part(languageTag(stack.material()), stack.material(), stack.amount());
    }

    /**
     * Тег языка клиента для материала: {@code <lang:item.minecraft.diamond>}
     * или {@code <lang:block.minecraft.stone>} для блоков.
     *
     * <p>{@code Material#isBlock()} на Paper обращается к реестру блоков, а он
     * существует только на живом сервере. В юнит-тестах вызов бросает — тогда
     * материал считается предметом, и тег всё равно остаётся корректным
     * MiniMessage. Неизвестный материал показывается как есть, без тега.</p>
     */
    public static String languageTag(String materialName) {
        String path = materialName == null ? "" : materialName.trim().toLowerCase(Locale.ROOT);
        if (path.isEmpty()) {
            return "";
        }
        try {
            Material material = Material.matchMaterial(materialName.trim());
            if (material == null) {
                return materialName.trim();
            }
            String prefix = material.isBlock() ? "block.minecraft." : "item.minecraft.";
            return "<lang:" + prefix + path + ">";
        } catch (Throwable ignored) {
            // Реестр блоков недоступен (тесты) — предмет как предмет.
            return "<lang:item.minecraft." + path + ">";
        }
    }
}
