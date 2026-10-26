package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Падающие звёзды события (1.4.176).
 *
 * <p>Раньше «Звездопад» был только надбавкой к руде: имя обещало небо, а в небе
 * ничего не происходило. Теперь у события есть вторая половина — время от
 * времени над случайным игроком открывается падающая звезда: огненный блок
 * летит с высоты, оставляет след и рассыпается добычей. Блоки она не ломает и
 * не ставит: в точке падения только предметы.</p>
 *
 * <p>Настройки — секция {@code meteors} у события. Всё зажато в конструкторе:
 * конфиг с опечаткой не сделает град из звёзд или бесконечную добычу.</p>
 *
 * @param enabled         включены ли звёзды у этого события
 * @param intervalSeconds пауза между звёздами (5..600)
 * @param radius          радиус вокруг случайного игрока (8..96 блоков)
 * @param maxActive       сколько звёзд может лететь одновременно (1..6)
 * @param blocks          из чего состоит звезда (падающий блок)
 * @param loot            что остаётся на земле
 * @param minLoot         минимум предметов (1..8)
 * @param maxLoot         максимум предметов (не меньше минимума)
 * @param announce        сообщать ли игрокам рядом, что звезда упала
 * @param breakBlocks     выбивать ли воронку в точке падения (только вне приватов)
 * @param craterRadius    радиус воронки в блоках (0 — не выбивать)
 */
public record MeteorSpawn(boolean enabled, int intervalSeconds, int radius, int maxActive,
                          List<Material> blocks, List<Material> loot, int minLoot, int maxLoot,
                          boolean announce, boolean breakBlocks, int craterRadius) {

    /** Чаще раза в пять секунд небо не должно сыпаться. */
    public static final int MIN_INTERVAL_SECONDS = 5;
    public static final int MAX_INTERVAL_SECONDS = 600;
    /** Меньше восьми блоков — звезда падает почти в голову. */
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 96;
    /** Потолок одновременных звёзд: больше — уже не событие, а салют. */
    public static final int MAX_ACTIVE = 6;
    /** Потолок добычи с одной звезды. */
    public static final int MAX_LOOT_PER_STAR = 8;
    /** Потолок воронки: больше — уже не след звезды, а карьер. */
    public static final int MAX_CRATER_RADIUS = 4;
    /** Выключенные звёзды: у события без секции {@code meteors}. */
    public static final MeteorSpawn NONE = new MeteorSpawn(false, 25, 24, 2,
            List.of(), List.of(), 1, 2, true, false, 0);

    public MeteorSpawn {
        intervalSeconds = clamp(intervalSeconds, MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS);
        radius = clamp(radius, MIN_RADIUS, MAX_RADIUS);
        maxActive = clamp(maxActive, 1, MAX_ACTIVE);
        blocks = copyMaterials(blocks);
        loot = copyMaterials(loot);
        minLoot = clamp(minLoot, 1, MAX_LOOT_PER_STAR);
        maxLoot = clamp(maxLoot, minLoot, MAX_LOOT_PER_STAR);
        craterRadius = clamp(craterRadius, 0, MAX_CRATER_RADIUS);
        if (!breakBlocks) {
            craterRadius = 0;
        }
    }

    /**
     * Настройки без воронки: прежний набор из девяти значений.
     *
     * <p>Оставлен, чтобы код и тесты, написанные до 1.4.176, продолжали
     * собираться: по умолчанию звезда мир не трогает.</p>
     */
    public MeteorSpawn(boolean enabled, int intervalSeconds, int radius, int maxActive,
                       List<Material> blocks, List<Material> loot, int minLoot, int maxLoot,
                       boolean announce) {
        this(enabled, intervalSeconds, radius, maxActive, blocks, loot, minLoot, maxLoot,
                announce, false, 0);
    }

    /** Выбивает ли звезда воронку в этой точке. */
    public boolean crater() {
        return breakBlocks && craterRadius > 0;
    }

    /** Звезда вообще может упасть: есть из чего и что оставить на земле. */
    public boolean usable() {
        return enabled && !blocks.isEmpty() && !loot.isEmpty();
    }

    /** Случайный блок звезды. */
    public Material block(Random random) {
        return pick(blocks, random);
    }

    /** Случайный предмет из добычи. */
    public Material lootItem(Random random) {
        return pick(loot, random);
    }

    /** Сколько предметов оставить: от {@code min-loot} до {@code max-loot}. */
    public int lootAmount(Random random) {
        if (maxLoot <= minLoot) {
            return minLoot;
        }
        return minLoot + random.nextInt(maxLoot - minLoot + 1);
    }

    private static Material pick(List<Material> values, Random random) {
        if (values.isEmpty()) {
            return Material.AIR;
        }
        return values.get(random.nextInt(values.size()));
    }

    private static List<Material> copyMaterials(List<Material> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Material> copy = new ArrayList<>();
        for (Material material : source) {
            if (EventMaterials.isItem(material) && !copy.contains(material)) {
                copy.add(material);
            }
        }
        return List.copyOf(copy);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
