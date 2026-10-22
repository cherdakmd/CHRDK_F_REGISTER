package ru.cherdakmd.chrdkpets;

import org.bukkit.DyeColor;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Wolf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ошейник питомца — бесплатная косметика.
 *
 * <p>У ванильных кошек и волков есть цвет ошейника; модуль запоминает выбор в
 * {@code pets.json} и восстанавливает его при загрузке. Косметика не даёт
 * никаких бонусов и не продаётся — это просто способ сделать питомца
 * узнаваемым. Цвета задаются короткими идентификаторами ({@code red},
 * {@code light_blue}), а их русские названия лежат в {@code messages.yml}.</p>
 */
public final class PetCollar {

    private static final List<String> IDS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private PetCollar() {
    }

    /** Все идентификаторы цветов в порядке ванильных {@link DyeColor}. */
    public static List<String> ids() {
        return IDS;
    }

    /** Цвет по идентификатору; {@code null} — неизвестный. */
    public static DyeColor of(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (String candidate : IDS) {
            if (candidate.equals(normalized)) {
                return DyeColor.valueOf(candidate.toUpperCase(Locale.ROOT));
            }
        }
        return null;
    }

    /** Идентификатор цвета по enum; пустая строка — цвет не задан. */
    public static String idOf(DyeColor color) {
        return color == null ? "" : color.name().toLowerCase(Locale.ROOT);
    }

    /** Идентификатор текущего ошейника сущности; пустая строка — не кошка и не волк. */
    public static String current(Entity entity) {
        if (entity instanceof Wolf wolf) {
            return idOf(wolf.getCollarColor());
        }
        if (entity instanceof Cat cat) {
            return idOf(cat.getCollarColor());
        }
        return "";
    }

    /**
     * Красит ошейник. Возвращает {@code false}, если сущность не кошка и не
     * волк — вызывающий код тогда сообщает игроку, что красить нечего.
     */
    public static boolean apply(Entity entity, DyeColor color) {
        if (color == null || entity == null) {
            return false;
        }
        if (entity instanceof Wolf wolf) {
            wolf.setCollarColor(color);
            return true;
        }
        if (entity instanceof Cat cat) {
            cat.setCollarColor(color);
            return true;
        }
        return false;
    }

    /** Идентификаторы цветов, начинающиеся с введённого текста (для tab-complete). */
    public static List<String> matching(String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String id : IDS) {
            if (id.startsWith(normalized)) {
                out.add(id);
            }
        }
        return out;
    }
}
