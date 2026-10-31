package ru.cherdakmd.chrdkartifacts;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;

/**
 * Рантайм уникальных свойств артефактов (волна 19).
 *
 * <p>Артефакты работают из инвентаря, а не при надевании, поэтому свойства
 * нельзя держать модификаторами самого предмета. Здесь два канала:</p>
 * <ul>
 *   <li>атрибутные свойства — транзитные модификаторы игрока с ключом
 *       {@code trait_<id>}. Повторное применение заменяет модификатор, а не
 *       плодит дубли; после снятия артефакта модификатор убирается пульсом;</li>
 *   <li>зельевые свойства — эффект с запасом длительности, как у пассивных
 *       артефактов. Силу внешнего эффекта не понижаем.</li>
 * </ul>
 *
 * <p>Несколько одинаковых свойств с разных артефактов не складываются: берётся
 * максимальный уровень (см. {@link ArtifactItems#carriedTraitLevels(Player)}).</p>
 */
public final class ArtifactTraitService {

    private final ArtifactsPlugin plugin;

    public ArtifactTraitService(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Применяет набор свойств к игроку. Отсутствующие в карте свойства снимаются —
     * поэтому метод же служит очисткой ({@link #clear(Player)}).
     */
    public void apply(Player player, Map<String, Integer> traits) {
        if (player == null || !player.isOnline()) {
            return;
        }
        applyAttributes(player, traits);
        refreshPotions(player, traits);
    }

    /** Зельевые свойства обновляет только штатный пульс, не события инвентаря. */
    public void refreshPotions(Player player, Map<String, Integer> traits) {
        if (player == null || !player.isOnline()) return;
        for (ArtifactTraits.Def def : ArtifactTraits.all()) {
            int level = traits == null ? 0 : traits.getOrDefault(def.id(), 0);
            if (def.kind() == ArtifactTraits.Kind.POTION && level > 0) {
                applyPotion(player, def, level);
            }
        }
    }

    /** Снимает все атрибутные свойства (зельевые истекают сами). */
    public void clear(Player player) {
        applyAttributes(player, Map.of());
    }

    /** Только атрибуты: безопасная синхронизация после изменения инвентаря без зельевых триггеров.
     * Очистка допускается и во время выхода игрока, когда isOnline уже изменился.
     */
    public void applyAttributes(Player player, Map<String, Integer> traits) {
        if (player == null) return;
        for (ArtifactTraits.Def def : ArtifactTraits.all()) {
            if (def.kind() != ArtifactTraits.Kind.POTION) {
                applyAttribute(player, def, traits == null ? 0 : traits.getOrDefault(def.id(), 0));
            }
        }
    }

    private void applyAttribute(Player player, ArtifactTraits.Def def, int level) {
        Attribute attribute = attributeOf(def.kind());
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        NamespacedKey key = new NamespacedKey(plugin, "trait_" + def.id());
        ArtifactTraitModifiers.replace(instance, key, def, level);
    }

    private void applyPotion(Player player, ArtifactTraits.Def def, int level) {
        PotionEffectType type = Potions.resolve(def.potionType());
        if (type == null) {
            return;
        }
        int duration = PotionRefresh.durationTicks(plugin.config().refreshTicks(), type.isInstant());
        int amplifier = def.amplifier(level);
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && !PotionRefresh.shouldApply(true, current.getAmplifier(),
                current.getDuration(), amplifier, duration)) {
            return;
        }
        player.addPotionEffect(new PotionEffect(type, duration, amplifier, true, false, true));
    }

    private static Attribute attributeOf(ArtifactTraits.Kind kind) {
        return switch (kind) {
            case ATTACK_DAMAGE -> Attribute.ATTACK_DAMAGE;
            case ATTACK_SPEED -> Attribute.ATTACK_SPEED;
            case ARMOR -> Attribute.ARMOR;
            case MOVEMENT_SPEED -> Attribute.MOVEMENT_SPEED;
            case MAX_HEALTH -> Attribute.MAX_HEALTH;
            case LUCK -> Attribute.LUCK;
            case KNOCKBACK_RESISTANCE -> Attribute.KNOCKBACK_RESISTANCE;
            case OXYGEN -> Attribute.OXYGEN_BONUS;
            case SAFE_FALL -> Attribute.SAFE_FALL_DISTANCE;
            case BLOCK_BREAK_SPEED -> Attribute.BLOCK_BREAK_SPEED;
            case SNEAKING_SPEED -> Attribute.SNEAKING_SPEED;
            case REACH -> Attribute.ENTITY_INTERACTION_RANGE;
            case BURNING_TIME -> Attribute.BURNING_TIME;
            case POTION -> null;
        };
    }
}
