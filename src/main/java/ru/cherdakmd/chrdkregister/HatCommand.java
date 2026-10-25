package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /hat} — надеть блок из руки на голову (1.4.167, преимущество Покровителя).
 *
 * <p>Единственная команда лестницы рангов. Она не даёт ни силы, ни защиты:
 * надеть можно только блок, а шлем, который был на голове, возвращается в руку.
 * Если в руке не блок — отказ с понятным текстом, чтобы нельзя было спрятать
 * в голову меч или элитру.</p>
 *
 * <p>Доступ: ступень ранга не ниже {@code ranks.hat.min-level} (по умолчанию 5)
 * или право {@code chrdkregister.hat} — оно у администраторов, чтобы можно было
 * проверить команду без покупки.</p>
 */
public final class HatCommand implements CommandExecutor {

    private final RegisterPlugin plugin;
    private final AccessManager access;
    private Messages messages;

    public HatCommand(RegisterPlugin plugin, AccessManager access) {
        this.plugin = plugin;
        this.access = access;
        this.messages = plugin.messages();
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void refreshMessages() {
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        int minLevel = Math.max(1, access.config().ranks().hatMinLevel());
        int level = access.store().rankLevel(player.getName(), System.currentTimeMillis());
        if (level < minLevel && !player.hasPermission("chrdkregister.hat")) {
            messages.send(player, "hat.locked", "level", minLevel,
                    "rank", access.config().ranks().decorate(minLevel,
                            access.config().ranks().name(minLevel)));
            return true;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            messages.send(player, "hat.empty-hand");
            return true;
        }
        if (!isWearable(hand.getType())) {
            messages.send(player, "hat.not-block", "item", hand.getType().name());
            return true;
        }
        ItemStack previous = inventory.getHelmet();
        inventory.setItemInMainHand(previous == null || previous.getType().isAir()
                ? null : previous);
        inventory.setHelmet(hand);
        messages.send(player, "hat.equipped");
        return true;
    }

    /**
     * Что можно надеть: блок и только блок.
     *
     * <p>Проверка через {@code isBlock()} отсекает мечи, элитры, щиты и еду.
     * Тыква и резная тыква — блоки, поэтому работают: это классические «шляпы».</p>
     */
    static boolean isWearable(Material material) {
        return material != null && material.isBlock() && !material.isAir();
    }
}
