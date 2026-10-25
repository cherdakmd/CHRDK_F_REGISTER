package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Аура хозяина: мягкий бонус, пока рядом живёт его питомец.
 *
 * <p>Аура намеренно маленькая и не складывается: у игрока может быть пять
 * волков, но бонус считается только от ближайшего живого питомца. Иначе ферма
 * питомцев стала бы способом получить бесконечную скорость. Значения растут с
 * уровнем и упираются в потолки из конфига.</p>
 *
 * <p>Модификаторы транзитные (не попадают в NBT): перезаход или рестарт их
 * снимает, а следующий пульс ставит заново. Поэтому выключение модуля никогда
 * не оставляет игроку «вечную» скорость.</p>
 */
public final class PetAura {

    private final PetsPlugin plugin;
    private final NamespacedKey speedKey;
    private final NamespacedKey luckKey;

    PetAura(PetsPlugin plugin) {
        this.plugin = plugin;
        this.speedKey = new NamespacedKey(plugin, "aura_speed");
        this.luckKey = new NamespacedKey(plugin, "aura_luck");
    }

    /** Пересчитывает ауру всем онлайн-игрокам. Вызывается пульсом и при reload. */
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Пересчитывает ауру одного игрока. */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!plugin.config().enabled()) {
            clear(player);
            return;
        }
        LivingEntity pet = plugin.service().nearestOwned(player, plugin.config().ownerRadius());
        PetStore.PetData data = pet == null ? null : plugin.service().data(pet);
        if (data == null || data.dead()) {
            clear(player);
            return;
        }
        PetBuffs.Stats stats = PetBuffs.stats(
                plugin.config().species(data.species()).buffs(),
                plugin.levelOf(pet.getUniqueId()));
        PetAttributes.apply(player, Attribute.MOVEMENT_SPEED, speedKey,
                stats.ownerSpeedScalar(), AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        PetAttributes.apply(player, Attribute.LUCK, luckKey, stats.ownerLuck(),
                AttributeModifier.Operation.ADD_NUMBER);
    }

    /** Снимает ауру с игрока (выход, смерть питомца, выключение модуля). */
    public void clear(Player player) {
        if (player == null) {
            return;
        }
        PetAttributes.remove(player, Attribute.MOVEMENT_SPEED, speedKey);
        PetAttributes.remove(player, Attribute.LUCK, luckKey);
    }

    /** Снимает ауру со всех онлайн-игроков. */
    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
        }
    }
}
