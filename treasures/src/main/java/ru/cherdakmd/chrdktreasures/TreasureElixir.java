package ru.cherdakmd.chrdktreasures;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Эликсиры из кладов (1.4.157): зелья с настоящими эффектами.
 *
 * <p>В {@code loot.yml} давно лежали строки вида {@code POTION} с именем
 * «Зелье поглощения», но получалась просто склянка воды с подписью: эффекта у
 * неё не было. Эликсир описывает эффект данными — ид, длительность и уровень, —
 * а сборка предмета в {@link TreasureItems} превращает это в обычное зелье,
 * которое действительно действует при выпивании.</p>
 *
 * <p>Иды эффектов — те же, что у Minecraft ({@code speed}, {@code haste},
 * {@code night_vision}); имена в чате и на предмете берутся из
 * {@code messages.yml} ({@code elixir.<ид>.name}), а строки эффектов — из
 * языкового тега клиента, поэтому «Скорость II · 90 с» звучит по-русски у
 * русского клиента и по-английски у английского.</p>
 *
 * <p>Класс чистый: ни Bukkit, ни реестров — иды проверяются юнит-тестами, а
 * {@code Registry.EFFECT} подключается только при сборке предмета.</p>
 */
public record TreasureElixir(String id, int red, int green, int blue, boolean glint,
                             List<Effect> effects) {

    /** Сколько эликсир действует и насколько сильно. */
    public record Effect(String type, int seconds, int amplifier) {

        /** Самое короткое действие: мгновенные эффекты в кладах не выдаются. */
        public static final int MIN_SECONDS = 5;
        /** Самое долгое действие: эликсир — не бесконечный бафф. */
        public static final int MAX_SECONDS = 600;
        /** Потолок уровня: III — уже «эликсир из мифического клада». */
        public static final int MAX_AMPLIFIER = 2;

        public Effect {
            type = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                throw new IllegalArgumentException("у эффекта эликсира нет ида");
            }
            seconds = Math.clamp(seconds, MIN_SECONDS, MAX_SECONDS);
            amplifier = Math.clamp(amplifier, 0, MAX_AMPLIFIER);
        }

        /** Римская запись уровня для подписи: I, II, III. */
        public String level() {
            return switch (amplifier) {
                case 0 -> "I";
                case 1 -> "II";
                default -> "III";
            };
        }
    }

    public TreasureElixir {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("у эликсира нет ида");
        }
        if (!id.equals(id.replaceAll("[^a-z0-9_-]", ""))) {
            throw new IllegalArgumentException("в иде эликсира недопустимые символы: " + id);
        }
        red = Math.clamp(red, 0, 255);
        green = Math.clamp(green, 0, 255);
        blue = Math.clamp(blue, 0, 255);
        effects = List.copyOf(effects == null ? List.of() : effects);
        if (effects.isEmpty()) {
            throw new IllegalArgumentException("у эликсира нет эффектов: " + id);
        }
    }

    /** Ключ названия в {@code messages.yml}. */
    public String nameKey() {
        return "elixir." + id + ".name";
    }

    /** Самый долгий эффект — по нему подписывается срок годности в лоре. */
    public int longestSeconds() {
        int longest = 0;
        for (Effect effect : effects) {
            longest = Math.max(longest, effect.seconds());
        }
        return longest;
    }

    /* -------------------------------- каталог -------------------------------- */

    private static final Map<String, TreasureElixir> BY_ID = build();

    /** Все эликсиры в порядке объявления. */
    public static List<TreasureElixir> all() {
        return List.copyOf(BY_ID.values());
    }

    /** Эликсир по иду; {@code null}, если такого нет. */
    public static TreasureElixir byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, TreasureElixir> build() {
        Map<String, TreasureElixir> out = new LinkedHashMap<>();
        add(out, new TreasureElixir("swiftness", 122, 208, 255, false,
                List.of(new Effect("speed", 90, 1))));
        add(out, new TreasureElixir("haste", 214, 178, 92, false,
                List.of(new Effect("haste", 120, 1))));
        add(out, new TreasureElixir("night_vision", 24, 42, 120, true,
                List.of(new Effect("night_vision", 180, 0))));
        add(out, new TreasureElixir("water_breathing", 62, 156, 214, false,
                List.of(new Effect("water_breathing", 120, 0),
                        new Effect("dolphins_grace", 60, 0))));
        add(out, new TreasureElixir("fire_resistance", 232, 116, 62, false,
                List.of(new Effect("fire_resistance", 120, 0))));
        add(out, new TreasureElixir("regeneration", 236, 110, 156, true,
                List.of(new Effect("regeneration", 45, 1))));
        add(out, new TreasureElixir("absorption", 226, 176, 68, false,
                List.of(new Effect("absorption", 90, 1))));
        add(out, new TreasureElixir("strength", 178, 40, 52, true,
                List.of(new Effect("strength", 60, 0))));
        add(out, new TreasureElixir("leaping", 140, 214, 118, false,
                List.of(new Effect("jump_boost", 120, 2))));
        add(out, new TreasureElixir("luck", 96, 196, 122, true,
                List.of(new Effect("luck", 300, 0))));
        add(out, new TreasureElixir("hero", 246, 208, 96, true,
                List.of(new Effect("hero_of_the_village", 120, 0),
                        new Effect("regeneration", 20, 0))));
        add(out, new TreasureElixir("insight", 150, 122, 240, true,
                List.of(new Effect("night_vision", 120, 0),
                        new Effect("haste", 45, 0))));
        return Collections.unmodifiableMap(out);
    }

    private static void add(Map<String, TreasureElixir> out, TreasureElixir elixir) {
        if (out.put(elixir.id(), elixir) != null) {
            throw new IllegalStateException("повторяющийся ид эликсира: " + elixir.id());
        }
    }
}
