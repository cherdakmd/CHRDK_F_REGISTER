package ru.cherdakmd.chrdkparty;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * События групп: бонус опыта, запрет дружеского огня и напоминание о
 * приглашениях (1.4.158).
 *
 * <p>Бонус считается по факту: сколько своих рядом <b>сейчас</b>. Никакого
 * накопления и никакой памяти между событиями — иначе бонус можно было бы
 * «заработать» один раз и носить с собой.</p>
 *
 * <p>Сообщения под действием троттлинга: опыт приходит пачками, и строка в
 * экшен-баре не должна превратиться в мигающую лампу.</p>
 */
public final class PartyListener implements Listener {

    /** Не чаще одного сообщения в 5 секунд на игрока. */
    private static final long MESSAGE_COOLDOWN_MS = 5_000L;

    private final PartyPlugin plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    PartyListener(PartyPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------------------------- опыт ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        if (player == null || event.getAmount() <= 0) {
            return;
        }
        int percent = plugin.service().bonusPercent(player.getUniqueId(),
                nearbyMembers(player));
        if (percent <= 0) {
            return;
        }
        int extra = (int) Math.round(event.getAmount() * (percent / 100.0));
        if (extra <= 0) {
            return;
        }
        event.setAmount(event.getAmount() + extra);
        if (cool(player.getUniqueId())) {
            player.sendActionBar(plugin.messages().get("bonus-xp",
                    "percent", String.valueOf(percent)));
        }
    }

    /** Сколько участников группы стоит рядом (сам игрок не считается). */
    private int nearbyMembers(Player player) {
        int radius = plugin.config().bonusRadius();
        UUID id = player.getUniqueId();
        Entity[] found = player.getWorld().getNearbyEntities(player.getLocation(),
                        radius, radius, radius,
                        entity -> entity instanceof Player other
                                && plugin.service().sameParty(id, other.getUniqueId()))
                .toArray(new Entity[0]);
        return found.length;
    }

    /* ------------------------------ дружеский огонь --------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (plugin.config().friendlyFire()) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!plugin.service().sameParty(attacker.getUniqueId(), victim.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (cool(attacker.getUniqueId())) {
            attacker.sendActionBar(plugin.messages().get("friendly-fire-blocked"));
        }
    }

    /* -------------------------------- приглашения ----------------------------- */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        int count = plugin.service().invitesFor(player.getUniqueId(),
                System.currentTimeMillis());
        if (count > 0) {
            plugin.messages().send(player, "join-invites",
                    "count", String.valueOf(count));
        }
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
        // Чистим словарь, чтобы он не рос бесконечно на большом сервере.
        if (lastMessage.size() > 512) {
            long cutoff = now - MESSAGE_COOLDOWN_MS;
            lastMessage.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        return true;
    }
}
