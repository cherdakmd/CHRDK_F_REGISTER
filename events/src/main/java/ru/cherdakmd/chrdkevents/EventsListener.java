package ru.cherdakmd.chrdkevents;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Действие событий на игрока (1.4.160): опыт, руда, дроп и вход в игру.
 *
 * <p>Все бонусы — надбавки к тому, что игрок и так делает. Ни один обработчик
 * не выдаёт предмет «из воздуха» без действия: руда падает только за
 * сломанный блок, дроп — только за убитого моба. Это главное отличие события
 * от сундука с подарками.</p>
 *
 * <p>С 1.4.176 здесь же эхо нашествия: во время «Кровавой луны» естественный
 * спавн рядом с игроком усиливается. Спавнеры, призывы команд и наши
 * собственные спавны ({@code CUSTOM}) множитель не получают.</p>
 *
 * <p>Сообщения в экшен-баре под троттлингом: опыт и руда приходят пачками, и
 * строка не должна мигать.</p>
 */
public final class EventsListener implements Listener {

    /** Не чаще одного сообщения в 4 секунды на игрока. */
    private static final long MESSAGE_COOLDOWN_MS = 4_000L;

    private final EventsPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    EventsListener(EventsPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------------------------- опыт ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        int percent = plugin.service().xpPercent();
        if (player == null || percent <= 0 || event.getAmount() <= 0) {
            return;
        }
        int extra = (int) Math.round(event.getAmount() * (percent / 100.0));
        if (extra <= 0) {
            return;
        }
        event.setAmount(event.getAmount() + extra);
        if (cool(player.getUniqueId())) {
            WorldEvent current = plugin.service().current();
            player.sendActionBar(plugin.messages().get("bonus-xp",
                    "percent", String.valueOf(percent),
                    "name", current == null ? "" : current.name()));
        }
    }

    /* ---------------------------------- руда ---------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        int percent = plugin.service().miningPercent();
        if (player == null || percent <= 0 || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        List<Material> blocks = plugin.service().miningBlocks();
        Material type = event.getBlock().getType();
        if (type == null || !blocks.contains(type)) {
            return;
        }
        if (random.nextInt(100) >= percent) {
            return;
        }
        give(player, new ItemStack(type, 1));
        if (cool(player.getUniqueId())) {
            WorldEvent current = plugin.service().current();
            player.sendActionBar(plugin.messages().get("bonus-mining",
                    "item", itemName(type),
                    "name", current == null ? "" : current.name()));
        }
    }

    /* ---------------------------------- дроп ---------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        int percent = plugin.service().lootPercent();
        if (killer == null || percent <= 0 || event.getDrops().isEmpty()) {
            return;
        }
        if (random.nextInt(100) >= percent) {
            return;
        }
        List<Material> items = plugin.service().lootItems();
        if (items.isEmpty()) {
            return;
        }
        Material picked = items.get(random.nextInt(items.size()));
        int money=ru.cherdakmd.chrdkregister.api.MoneyLoot.unit(picked.name());
        if(money>0){plugin.moneyLoot().accept(killer,"mob:"+event.getEntity().getUniqueId(),money);return;}
        event.getDrops().add(new ItemStack(picked, 1));
        if (cool(killer.getUniqueId())) {
            WorldEvent current = plugin.service().current();
            killer.sendActionBar(plugin.messages().get("bonus-loot",
                    "item", itemName(picked),
                    "name", current == null ? "" : current.name()));
        }
    }

    /* -------------------------------- нашествие -------------------------------- */

    /**
     * Естественный спавн во время нашествия: рядом поднимается ещё моб.
     *
     * <p>Проверка причины спавна здесь принципиальна: без неё фермы на
     * спавнерах и мобов, призванных командой, событие усиливало бы так же, как
     * честную ночную охоту.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (!(event.getEntity() instanceof Enemy)) {
            return;
        }
        HordeService horde = plugin.horde();
        if (horde != null) {
            horde.reinforce(event.getLocation(), event.getEntityType());
        }
    }

    /* ---------------------------------- вход ---------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.handleQuit(event.getPlayer());
    }

    /* --------------------------------- помощники ------------------------------ */

    /** Кладёт в инвентарь, а если места нет — рядом на землю. */
    private void give(Player player, ItemStack stack) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    /** Имя предмета для текста: {@code diamond_ore → Diamond ore}. */
    private String itemName(Material material) {
        String key = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /** Троттлинг сообщений: {@code true} — можно показать. */
    private boolean cool(UUID id) {
        if (id == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(id);
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
            return false;
        }
        lastMessage.put(id, now);
        if (lastMessage.size() > 512) {
            lastMessage.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
        }
        return true;
    }
}
