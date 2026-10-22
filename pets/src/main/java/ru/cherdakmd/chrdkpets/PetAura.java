package ru.cherdakmd.chrdkpets;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Аура хозяина: небольшой бонус, пока рядом живёт его питомец.
 *
 * <p>Правила намеренно жёсткие, чтобы питомцы не превратились в стопку
 * баффов: аура действует только на владельца, только от <b>одного</b>
 * ближайшего живого питомца и только в радиусе {@code general.owner-radius}.
 * Ушёл питомец, погиб, выключили модуль — модификаторы снимаются следующим
 * пульсом. Опыт и уровни за это не продаются: питомца нельзя купить, его
 * можно только приручить и вырастить.</p>
 */
final class PetAura {

    private static final String SPEED_KEY = "aura_speed";
    private static final String LUCK_KEY = "aura_luck";

    private final PetsPlugin plugin;
    private final NamespacedKey speedKey;
    private final NamespacedKey luckKey;

    PetAura(PetsPlugin plugin) {
        this.plugin = plugin;
        this.speedKey = new NamespacedKey(plugin, SPEED_KEY);
        this.luckKey = new NamespacedKey(plugin, LUCK_KEY);
    }

    /** Переставляет ауру по текущему питомцу игрока. */
    void refresh(Player player) {
        if (player == null) {
            return;
        }
        if (!plugin.config().enabled()) {
            clear(player);
            return;
        }
        LivingEntity pet = plugin.service().nearestOwned(player, plugin.config().ownerRadius());
        PetStore.PetData data = pet == null ? null : plugin.service().data(pet);
        if (data == null) {
            clear(player);
            return;
        }
        PetBuffs.Stats stats = PetBuffs.stats(
                plugin.config().species(data.species()).buffs(),
                plugin.service().level(data));
        PetAttributes.set(player, Attribute.MOVEMENT_SPEED, speedKey,
                stats.ownerSpeedScalar(), AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        PetAttributes.set(player, Attribute.LUCK, luckKey,
                stats.ownerLuck(), AttributeModifier.Operation.ADD_NUMBER);
    }

    /** Снимает ауру с игрока. */
    void clear(Player player) {
        if (player == null) {
            return;
        }
        PetAttributes.remove(player, Attribute.MOVEMENT_SPEED, speedKey);
        PetAttributes.remove(player, Attribute.LUCK, luckKey);
    }

    /** Снимает ауру со всех онлайн-игроков (выключение, reload). */
    void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clear(player);
        }
    }
}
