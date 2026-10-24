package ru.cherdakmd.chrdkclasses;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пассивные эффекты класса в атрибутах игрока (1.4.164).
 *
 * <p>Транзитные модификаторы, как у навыков: они не попадают в playerdata и не
 * переживают перезаход, поэтому «класс усилили, потом убрали, а броня осталась»
 * невозможно. Ключи свои ({@code chrdkclasses_*}), поэтому класс не конфликтует
 * с навыками ({@code chrdkskills_*}) и тир-предметами.</p>
 *
 * <p>Здоровье и броня идут прибавкой к базе, скорость — множителем (ванильная
 * база ходьбы 0,1), сопротивление отбрасыванию — тоже прибавкой. Урон, лечение
 * и насыщение считаются в обработчиках событий и пульсе: их нельзя выразить
 * атрибутом без побочных эффектов на другие аддоны.</p>
 */
final class ClassEffects {

    private static final String HEALTH_KEY = "chrdkclasses_health";
    private static final String ARMOR_KEY = "chrdkclasses_armor";
    private static final String SPEED_KEY = "chrdkclasses_speed";
    private static final String KNOCKBACK_KEY = "chrdkclasses_knockback";

    private final ClassesPlugin plugin;
    /** Момент последнего урона по игроку: вне боя работает регенерация. */
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();

    ClassEffects(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /* -------------------------------- атрибуты -------------------------------- */

    /** Применяет (или снимает) атрибутные эффекты по текущему дереву. */
    void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        TalentService service = plugin.service();
        if (service == null || !plugin.config().effectsEnabled()) {
            clear(player);
            return;
        }
        Map<EffectKind, Double> effects = service.effects(player.getUniqueId());
        applyModifier(player, Attribute.MAX_HEALTH, HEALTH_KEY,
                effects.getOrDefault(EffectKind.MAX_HEALTH, 0.0),
                AttributeModifier.Operation.ADD_NUMBER);
        applyModifier(player, Attribute.ARMOR, ARMOR_KEY,
                effects.getOrDefault(EffectKind.ARMOR, 0.0),
                AttributeModifier.Operation.ADD_NUMBER);
        applyModifier(player, Attribute.MOVEMENT_SPEED, SPEED_KEY,
                effects.getOrDefault(EffectKind.MOVEMENT_SPEED, 0.0),
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        applyModifier(player, Attribute.KNOCKBACK_RESISTANCE, KNOCKBACK_KEY,
                effects.getOrDefault(EffectKind.KNOCKBACK_RESISTANCE, 0.0),
                AttributeModifier.Operation.ADD_NUMBER);
        clampHealth(player);
    }

    /** Снимает все модификаторы классов (выключение, выход, смерть). */
    void clear(Player player) {
        if (player == null) {
            return;
        }
        removeModifier(player, Attribute.MAX_HEALTH, HEALTH_KEY);
        removeModifier(player, Attribute.ARMOR, ARMOR_KEY);
        removeModifier(player, Attribute.MOVEMENT_SPEED, SPEED_KEY);
        removeModifier(player, Attribute.KNOCKBACK_RESISTANCE, KNOCKBACK_KEY);
        clampHealth(player);
        lastCombat.remove(player.getUniqueId());
    }

    private void applyModifier(Player player, Attribute attribute, String key, double amount,
                               AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        NamespacedKey namespaced = new NamespacedKey(plugin, key);
        if (instance.getModifier(namespaced) != null) {
            instance.removeModifier(namespaced);
        }
        if (amount <= 0.0) {
            return;
        }
        try {
            instance.addTransientModifier(new AttributeModifier(namespaced, amount, operation));
        } catch (IllegalArgumentException ignored) {
            // Атрибут не принял модификатор — класс просто не даст эту прибавку.
        }
    }

    private void removeModifier(Player player, Attribute attribute, String key) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        NamespacedKey namespaced = new NamespacedKey(plugin, key);
        if (instance.getModifier(namespaced) != null) {
            instance.removeModifier(namespaced);
        }
    }

    /** Здоровье не должно оставаться выше нового максимума (сняли класс — не «раненый бог»). */
    private void clampHealth(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) {
            return;
        }
        double limit = max.getValue();
        if (player.getHealth() > limit) {
            player.setHealth(Math.max(1.0, limit));
        }
    }

    /* ----------------------------------- бой ---------------------------------- */

    /** Запоминает момент урона: пока он свежий, регенерация молчит. */
    void markCombat(UUID player, long now) {
        if (player != null) {
            lastCombat.put(player, now);
        }
    }

    /** Был ли игрок в бою в пределах окна конфига. */
    boolean inCombat(UUID player, long now) {
        if (player == null) {
            return false;
        }
        Long moment = lastCombat.get(player);
        return moment != null && now - moment < plugin.config().outOfCombatMillis();
    }

    void forget(UUID player) {
        if (player != null) {
            lastCombat.remove(player);
        }
    }

    /* ---------------------------------- пульс --------------------------------- */

    /**
     * Один такт: лечение и насыщение вне боя.
     *
     * <p>Регенерация не работает в бою и не поднимает здоровье выше максимума;
     * насыщение только восполняет голод, а не накапливает «бесконечную еду».</p>
     */
    void pulse(Player player, long now) {
        if (player == null || !player.isOnline() || player.isDead()
                || !plugin.config().effectsEnabled() || inCombat(player.getUniqueId(), now)) {
            return;
        }
        TalentService service = plugin.service();
        if (service == null) {
            return;
        }
        Map<EffectKind, Double> effects = service.effects(player.getUniqueId());
        double regen = effects.getOrDefault(EffectKind.REGEN, 0.0);
        if (regen > 0.0) {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            double limit = max == null ? 20.0 : max.getValue();
            if (player.getHealth() < limit) {
                player.setHealth(Math.min(limit, player.getHealth() + regen / 2.0));
            }
        }
        double saturation = effects.getOrDefault(EffectKind.SATURATION, 0.0);
        if (saturation > 0.0 && player.getFoodLevel() < 20) {
            player.setFoodLevel(20);
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + saturation));
        }
    }
}
