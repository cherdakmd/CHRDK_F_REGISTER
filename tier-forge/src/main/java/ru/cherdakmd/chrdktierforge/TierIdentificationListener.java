package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

/**
 * Сервис идентификации: «неопознанный» предмет превращается в тир-предмет ТОЛЬКО
 * после того, как попал в инвентарь игрока (главный запрос 1.4.44: редкость
 * нельзя подглядеть на земле/в сетке крафта).
 *
 * <p>Два канала: (1) мгновенный — подбор с земли ({@link EntityPickupItemEvent});
 * (2) фоновый скан инвентарей всех онлайн-игроков раз в {@code identify.scan-ticks}
 * — резерв для крафта/воронок/сундуков/торговли. Ролл при опознании всегда выдаёт
 * тир (сам выбор «это предмет 롤лится» сделан при пометке — по шансам источника).</p>
 */
public final class TierIdentificationListener implements Listener {

    private final TierForgePlugin plugin;
    private final Random random = new Random();

    public TierIdentificationListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Мгновенная идентификация при подборе с земли. */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (!plugin.items().isUnidentified(stack)) {
            return;
        }
        identify(player, stack, "pickup");
        event.getItem().setItemStack(stack);
    }

    /** Периодический скан: ловит крафт, воронки, сундуки, торговлю, наковальн-смитинг. */
    public void scanAll() {
        if (!plugin.config().identifyUnidentified()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scanPlayer(player);
        }
    }

    public void scanPlayer(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        boolean dirty = false;
        for (ItemStack stack : contents) {
            if (stack == null || !plugin.items().isUnidentified(stack)) {
                continue;
            }
            identify(player, stack, "scan");
            dirty = true;
        }
        if (dirty) {
            player.updateInventory();
        }
    }

    /** Опознать один предмет: ролл тира, применение, сообщение/звук, статистика. */
    public void identify(Player player, ItemStack stack, String channel) {
        String source = plugin.items().unidentifiedSource(stack);
        plugin.items().clearUnidentified(stack);

        TierDef.Snapshot roll = TierRoller.roll(plugin.config().tiers(), random);
        if (roll == null) {
            return; // страховка: роллер никогда не должен вернуть null для неопознанного
        }
        plugin.items().apply(stack, roll);
        plugin.stats().count(player.getUniqueId(), roll.id());

        if ("cursed".equals(roll.id())) {
            plugin.messages().send(player, "identified-cursed");
            playSound(player, roll);
            return;
        }
        plugin.messages().send(player, "identified",
                "tier", plugin.messages().raw("tier." + roll.id() + ".name"));
        playSound(player, roll);
        logHighTier(player, stack, roll, source, channel);
    }

    /** Звуковой акцент по «мощности» тира (опционально отключается в конфиге). */
    private void playSound(Player player, TierDef.Snapshot roll) {
        if (!plugin.config().identifySounds()) {
            return;
        }
        try {
            if ("cursed".equals(roll.id())) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.8f);
            } else if (TierDef.indexOf(roll.id()) >= TierDef.indexOf("epic")) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
            } else if (TierDef.indexOf(roll.id()) >= TierDef.indexOf("rare")) {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.1f);
            }
        } catch (IllegalArgumentException exception) {
            // звук пропал в новой версии — не критично, молча идём дальше
            plugin.getLogger().fine("identify sound skipped: " + exception.getMessage());
        }
    }

    /** Консоль-аудит rare+ накатов (для хозяина сервера). */
    private void logHighTier(Player player, ItemStack stack, TierDef.Snapshot roll,
                             String source, String channel) {
        if (TierDef.indexOf(roll.id()) < TierDef.indexOf("rare")) {
            return;
        }
        plugin.getLogger().info("Tier-раскат rare+: " + player.getName() + " получил "
                + stack.getType().name() + " (" + roll.id() + ") "
                + "[source=" + (source.isBlank() ? "?" : source)
                + ", via=" + channel + "]");
    }
}
