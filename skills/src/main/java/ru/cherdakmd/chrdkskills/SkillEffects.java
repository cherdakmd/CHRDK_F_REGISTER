package ru.cherdakmd.chrdkskills;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

/**
 * Атрибутные перки: скорость разрушения блоков и броня.
 *
 * <p>Почему транзитные модификаторы, а не эффекты зелий: зелье «спешка» меняет
 * и скорость атаки, и визуал инвентаря, а чужие плагины считают его баффом.
 * Модификатор на {@code BLOCK_BREAK_SPEED} действует ровно на копание, а
 * {@code ARMOR} — на входящий урон, и оба не попадают в playerdata.</p>
 *
 * <p>Ключи свои ({@code chrdkskills_mining_speed}, {@code chrdkskills_defense_armor}),
 * поэтому перки не конфликтуют с тир-предметами и артефактами: те вешают
 * модификаторы на предметы и на другие ключи.</p>
 */
public final class SkillEffects {

    private final SkillsPlugin plugin;

    public SkillEffects(SkillsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Применяет (или снимает) атрибутные перки по текущим уровням. */
    public void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        SkillPerks.Active mining = plugin.skills().perksFor(player, SkillType.MINING);
        SkillPerks.Active defense = plugin.skills().perksFor(player, SkillType.DEFENSE);
        applyModifier(player, Attribute.BLOCK_BREAK_SPEED, "chrdkskills_mining_speed",
                mining.value(SkillPerk.Kind.BREAK_SPEED),
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        applyModifier(player, Attribute.ARMOR, "chrdkskills_defense_armor",
                defense.value(SkillPerk.Kind.ARMOR),
                AttributeModifier.Operation.ADD_NUMBER);
    }

    /** Снимает все модификаторы навыков (выключение, потеря прав). */
    public void clear(Player player) {
        if (player == null) {
            return;
        }
        removeModifier(player, Attribute.BLOCK_BREAK_SPEED, "chrdkskills_mining_speed");
        removeModifier(player, Attribute.ARMOR, "chrdkskills_defense_armor");
    }

    private void applyModifier(Player player, Attribute attribute, String key,
                               double amount, AttributeModifier.Operation operation) {
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
            // атрибут не принял модификатор — перк просто не сработает
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
}
