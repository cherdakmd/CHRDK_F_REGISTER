package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Описание мирового события из {@code config.yml} (1.4.160).
 *
 * <p>Событие — это имя, вид, длительность, вес выпадения и данные конкретного
 * вида: процент надбавки, список руды, список дропа или набор эффектов. Все
 * числа зажимаются в конструкторе, поэтому даже конфиг с опечаткой не сломает
 * сервер, а превратится в предупреждение в логе.</p>
 *
 * <p>Эффекты хранятся строкой: {@code PotionEffectType} в современных версиях
 * тянется через реестр, который поднимается только на живом сервере, а разбор
 * конфига должен проверяться тестами. Имя эффекта разрешается в момент выдачи.</p>
 *
 * @param id                ид в конфиге ({@code knowledge})
 * @param name              русское имя для чата
 * @param description       короткое пояснение, что даёт событие
 * @param kind              вид события
 * @param durationSeconds   сколько длится (30..1800)
 * @param weight            вес в выпадении (0..1000; 0 — только вручную)
 * @param cooldownSeconds   пауза после окончания (0..86400)
 * @param percent           процент надбавки или шанса (0..100)
 * @param blocks            руда для вида MINING
 * @param items             предметы для вида LOOT
 * @param effects           эффекты для вида EFFECT
 * @param icon              иконка в меню и профиле
 */
public record WorldEvent(String id, String name, String description, EventKind kind,
                         int durationSeconds, int weight, int cooldownSeconds, int percent,
                         List<Material> blocks, List<Material> items, List<Effect> effects,
                         Material icon) {

    /** Потолок длительности: дольше игроки перестают замечать событие. */
    public static final int MAX_DURATION_SECONDS = 1_800;
    /** Минимальная длительность: короче не успеть дойти. */
    public static final int MIN_DURATION_SECONDS = 30;
    /** Потолок веса: больше не нужно, но защищает от опечатки. */
    public static final int MAX_WEIGHT = 1_000;
    /** Потолок паузы: сутки. */
    public static final int MAX_COOLDOWN_SECONDS = 86_400;

    /** Эффект события: имя типа, уровень (0 — I) и длительность. */
    public record Effect(String type, int amplifier, int seconds) {

        public Effect {
            type = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
            amplifier = Math.clamp(amplifier, 0, 9);
            seconds = Math.clamp(seconds, 1, MAX_DURATION_SECONDS);
        }

        public boolean usable() {
            return !type.isEmpty();
        }
    }

    public WorldEvent {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? "" : description.trim();
        kind = kind == null ? EventKind.XP : kind;
        durationSeconds = Math.clamp(durationSeconds, MIN_DURATION_SECONDS, MAX_DURATION_SECONDS);
        weight = Math.clamp(weight, 0, MAX_WEIGHT);
        cooldownSeconds = Math.clamp(cooldownSeconds, 0, MAX_COOLDOWN_SECONDS);
        percent = Math.clamp(percent, 0, 100);
        blocks = copyMaterials(blocks);
        items = copyMaterials(items);
        effects = copyEffects(effects);
        icon = icon == null || icon == Material.AIR ? Material.CLOCK : icon;
    }

    private static List<Material> copyMaterials(List<Material> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Material> copy = new ArrayList<>();
        for (Material material : source) {
            if (material != null && material != Material.AIR && !copy.contains(material)) {
                copy.add(material);
            }
        }
        return List.copyOf(copy);
    }

    private static List<Effect> copyEffects(List<Effect> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Effect> copy = new ArrayList<>();
        for (Effect effect : source) {
            if (effect != null && effect.usable()) {
                copy.add(effect);
            }
        }
        return List.copyOf(copy);
    }

    /** Событие вообще может произойти: есть вес и данные для его вида. */
    public boolean usable() {
        if (weight <= 0 || durationSeconds <= 0) {
            return false;
        }
        return switch (kind) {
            case XP -> percent > 0;
            case MINING -> percent > 0 && !blocks.isEmpty();
            case LOOT -> percent > 0 && !items.isEmpty();
            case EFFECT -> !effects.isEmpty();
        };
    }

    /** Самый долгий эффект — им ограничивается «хвост» после события. */
    public int longestEffectSeconds() {
        int longest = 0;
        for (Effect effect : effects) {
            longest = Math.max(longest, effect.seconds());
        }
        return longest;
    }
}
