package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Курсор кругового скана: каждый тик обрабатывается следующая порция игроков. */
    private final AtomicInteger scanCursor = new AtomicInteger();

    /**
     * Периодический скан: ловит крафт, воронки, сундуки, торговлю, наковальн-смитинг.
     * С 1.4.86 обходит не всех сразу, а порцию — размер задаёт
     * {@code identify.scan-players-per-tick}; так стоимость тика не растёт с онлайном.
     */
    public void scanBatch(int playersPerTick) {
        if (!plugin.config().identifyUnidentified()) {
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            return;
        }
        int size = online.size();
        int budget = Math.min(Math.max(1, playersPerTick), size);
        int start = Math.floorMod(scanCursor.getAndAdd(budget), size);
        for (int i = 0; i < budget; i++) {
            scanPlayer(online.get((start + i) % size));
        }
    }

    public void scanPlayer(Player player) {
        // 1.4.80: getContents() включает надетую броню и левую руку — раньше
        // неопознанный предмет, надетый на себя, оставался «скрытым» до снятия.
        ItemStack[] contents = player.getInventory().getContents();
        List<ItemStack> pending = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack != null && plugin.items().isUnidentified(stack)) {
                pending.add(stack);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        // 1.4.94: вся пачка — одним сообщением и одним звуком.
        if (identifyAll(player, pending, "scan") > 0) {
            player.updateInventory();
        }
    }

    /** Опознать один предмет (подбор с земли). */
    public void identify(Player player, ItemStack stack, String channel) {
        if (stack == null) {
            return; // List.of(null) бросил бы NPE
        }
        identifyAll(player, List.of(stack), channel);
    }

    /**
     * Опознать пачку предметов: ролл, применение, статистика — на каждый предмет,
     * а сообщение и звук — одни на всю пачку (1.4.94). Раньше скан инвентаря
     * отправлял отдельное сообщение на каждую находку и забивал чат.
     *
     * @return сколько предметов реально получило тир.
     */
    public int identifyAll(Player player, List<ItemStack> stacks, String channel) {
        List<TierDef.Snapshot> rolls = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack == null || !plugin.items().isUnidentified(stack)) {
                continue;
            }
            String source = plugin.items().unidentifiedSource(stack);
            plugin.items().clearUnidentified(stack);
            TierDef.Snapshot roll = TierRoller.roll(plugin.config().tiers(), random);
            if (roll == null) {
                continue; // страховка: роллер никогда не должен вернуть null
            }
            plugin.items().apply(stack, roll);
            plugin.stats().count(player.getUniqueId(), roll.id());
            logHighTier(player, stack, roll, source, channel);
            rolls.add(roll);
        }
        IdentifyBatch batch = IdentifyBatch.of(rolls);
        if (batch.isEmpty()) {
            return 0;
        }
        if (batch.total() == 1) {
            TierDef.Snapshot roll = batch.best();
            if ("cursed".equals(roll.id())) {
                plugin.messages().send(player, "identified-cursed");
            } else {
                plugin.messages().send(player, "identified",
                        "tier", plugin.messages().raw("tier." + roll.id() + ".name"));
            }
        } else if (batch.allCursed()) {
            plugin.messages().send(player, "identified-batch-cursed", "count", batch.total());
        } else {
            plugin.messages().send(player, "identified-batch",
                    "count", batch.total(),
                    "tier", plugin.messages().raw("tier." + batch.best().id() + ".name"));
        }
        playSound(player, batch.best());
        return batch.total();
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
