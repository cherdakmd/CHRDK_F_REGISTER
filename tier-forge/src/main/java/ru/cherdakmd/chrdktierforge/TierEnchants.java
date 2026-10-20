package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Случайные ванильные зачарования на тир-предметах (волна 17).
 *
 * <p>Раньше тир-предмет получал только редкость, свойства и ковку — зачаровывать
 * его приходилось самому. Теперь при накате иногда «в комплекте» появляется
 * 1–2 зачарования: чем выше тир, тем чаще и тем выше уровень. Это не отменяет
 * стол зачарований и наковальню — игрок волен зачаровать предмет сам, и тогда
 * плагин ничего не трогает.</p>
 *
 * <p>Правила:</p>
 * <ul>
 *   <li>пул зачарований зависит от вида предмета (меч, топор, лук, арбалет,
 *       трезубец, шлем, нагрудник/штаны, ботинки, инструмент) — заведомо
 *       неподходящих комбинаций не бывает, а слоты брони получают только свои
 *       чары (дыхание — шлем, лёгкость падения — ботинки);</li>
 *   <li>взаимоисключающие чары (защита/огнезащита/взрывозащита/защита от
 *       снарядов, «Удача»/«Шёлковое касание», «Острота»/«Небесная кара»/«Бич
 *       членистоногих») в одном броске не выпадают вместе;</li>
 *   <li>предмет с любыми зачарованиями плагин не трогает — ни добавляет, ни
 *       снимает;</li>
 *   <li>бросок детерминирован по {@code uid} предмета и тиру, поэтому
 *       предпросмотр наковальни не переролливается.</li>
 * </ul>
 *
 * <p>Класс чистый: Bukkit не нужен, ключи зачарований хранятся строками и
 * разрешаются в {@link TierItems}.</p>
 */
public final class TierEnchants {

    /** Прибавка шанса за каждый тир выше проклятого (%). */
    public static final int PER_TIER_BONUS = 8;
    /** Шанс второго зачарования при {@code max-per-item} ≥ 2 (%). */
    public static final int SECOND_ROLL_PERCENT = 25;

    /**
     * Одно выпавшее зачарование.
     *
     * @param key   ключ в реестре Minecraft ({@code sharpness}, {@code unbreaking})
     * @param level уровень I..{@code max-level}
     */
    public record Roll(String key, int level) {
    }

    /**
     * Строка пула. {@code group} — семейство взаимоисключающих чар (пусто —
     * ни с чем не конфликтует).
     */
    private record Entry(String key, int maxLevel, String group) {
        private Entry(String key, int maxLevel) {
            this(key, maxLevel, "");
        }
    }

    private static final List<Entry> MELEE = List.of(
            new Entry("sharpness", 5, "weapon_damage"),
            new Entry("smite", 5, "weapon_damage"),
            new Entry("bane_of_arthropods", 5, "weapon_damage"),
            new Entry("knockback", 2),
            new Entry("fire_aspect", 2),
            new Entry("looting", 3),
            new Entry("sweeping_edge", 3),
            new Entry("unbreaking", 3));

    private static final List<Entry> BOW = List.of(
            new Entry("power", 5),
            new Entry("punch", 2),
            new Entry("flame", 1),
            new Entry("infinity", 1),
            new Entry("unbreaking", 3));

    /** У арбалета свой набор: сила/отброс/пламя ему недоступны. */
    private static final List<Entry> CROSSBOW = List.of(
            new Entry("quick_charge", 3),
            new Entry("multishot", 1, "crossbow_ammo"),
            new Entry("piercing", 4, "crossbow_ammo"),
            new Entry("unbreaking", 3));

    /** Топор — и оружие, и инструмент: кара/бич/эффективность/удача ванно допустимы. */
    private static final List<Entry> AXE = List.of(
            new Entry("sharpness", 5, "weapon_damage"),
            new Entry("smite", 5, "weapon_damage"),
            new Entry("bane_of_arthropods", 5, "weapon_damage"),
            new Entry("efficiency", 5),
            new Entry("fortune", 3, "tool_bonus"),
            new Entry("silk_touch", 1, "tool_bonus"),
            new Entry("unbreaking", 3));

    private static final List<Entry> TRIDENT = List.of(
            new Entry("loyalty", 3),
            new Entry("impaling", 5),
            new Entry("riptide", 3),
            new Entry("channeling", 1),
            new Entry("unbreaking", 3));

    /** Броня по слотам: дыхание и лёгкость падения осмысленны только на своих деталях. */
    private static final List<Entry> HELMET_POOL = List.of(
            new Entry("protection", 4, "protection"),
            new Entry("fire_protection", 4, "protection"),
            new Entry("blast_protection", 4, "protection"),
            new Entry("projectile_protection", 4, "protection"),
            new Entry("respiration", 3),
            new Entry("aqua_affinity", 1),
            new Entry("thorns", 3),
            new Entry("unbreaking", 3));

    private static final List<Entry> BODY_POOL = List.of(
            new Entry("protection", 4, "protection"),
            new Entry("fire_protection", 4, "protection"),
            new Entry("blast_protection", 4, "protection"),
            new Entry("projectile_protection", 4, "protection"),
            new Entry("thorns", 3),
            new Entry("unbreaking", 3));

    private static final List<Entry> BOOTS_POOL = List.of(
            new Entry("protection", 4, "protection"),
            new Entry("fire_protection", 4, "protection"),
            new Entry("blast_protection", 4, "protection"),
            new Entry("projectile_protection", 4, "protection"),
            new Entry("feather_falling", 4),
            new Entry("depth_strider", 3),
            new Entry("thorns", 3),
            new Entry("unbreaking", 3));

    private static final List<Entry> TOOL = List.of(
            new Entry("efficiency", 5),
            new Entry("fortune", 3, "tool_bonus"),
            new Entry("silk_touch", 1, "tool_bonus"),
            new Entry("unbreaking", 3));

    private TierEnchants() {
    }

    /** Пул зачарований для вида предмета (пустой — вид не тир-предмет). */
    public static List<Roll> pool(ItemKind kind) {
        return entries(kind).stream().map(entry -> new Roll(entry.key(), entry.maxLevel())).toList();
    }

    private static List<Entry> entries(ItemKind kind) {
        if (kind == null) {
            return List.of();
        }
        return switch (kind) {
            case SWORD -> MELEE;
            case AXE -> AXE;
            case BOW -> BOW;
            case CROSSBOW -> CROSSBOW;
            case TRIDENT -> TRIDENT;
            case PICKAXE, SHOVEL, HOE -> TOOL;
            case HELMET -> HELMET_POOL;
            case CHESTPLATE, LEGGINGS -> BODY_POOL;
            case BOOTS -> BOOTS_POOL;
        };
    }

    /** Шанс зачарования для тира: база из конфига + {@value #PER_TIER_BONUS}% за тир. */
    public static int chanceFor(int tierIndex, int basePercent) {
        int base = Math.max(0, Math.min(100, basePercent));
        if (tierIndex <= 0) {
            return base;
        }
        return Math.min(100, base + PER_TIER_BONUS * tierIndex);
    }

    /** Потолок уровня для тира: I–II внизу лестницы, III наверху. */
    public static int levelCap(int tierIndex, int maxLevel) {
        int cap = Math.min(Math.max(1, maxLevel), 1 + Math.max(0, tierIndex) / 2);
        return Math.max(1, cap);
    }

    /**
     * Бросок зачарований. Пустой список — шанс не прошёл, вид не подошёл или
     * зачарования выключены ({@code maxPerItem}/{@code maxLevel} ≤ 0).
     */
    public static List<Roll> roll(ItemKind kind, int tierIndex, Random random,
                                  int baseChancePercent, int maxPerItem, int maxLevel) {
        if (kind == null || random == null || maxPerItem <= 0 || maxLevel <= 0) {
            return List.of();
        }
        List<Entry> pool = entries(kind);
        if (pool.isEmpty()) {
            return List.of();
        }
        int chance = chanceFor(tierIndex, baseChancePercent);
        if (chance <= 0 || random.nextInt(100) >= chance) {
            return List.of();
        }
        int cap = levelCap(tierIndex, maxLevel);
        // Каждое следующее зачарование — отдельный бросок: 25% на второе,
        // 25% на третье. Так «два чара» не превращаются в «сразу три».
        int wanted = 1;
        while (wanted < Math.min(maxPerItem, pool.size())
                && random.nextInt(100) < SECOND_ROLL_PERCENT) {
            wanted++;
        }
        List<Entry> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        List<Roll> result = new ArrayList<>(wanted);
        Set<String> groups = new HashSet<>();
        for (Entry entry : shuffled) {
            if (result.size() >= wanted) {
                break;
            }
            if (!entry.group().isEmpty() && !groups.add(entry.group())) {
                continue;
            }
            int limit = Math.min(cap, entry.maxLevel());
            int level = 1 + random.nextInt(Math.max(1, limit));
            result.add(new Roll(entry.key(), level));
        }
        return List.copyOf(result);
    }

    /** Человеческое имя ключа для логов и диагностики: {@code fire_aspect} → Fire Aspect. */
    public static String displayName(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(key.length());
        for (String part : key.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
