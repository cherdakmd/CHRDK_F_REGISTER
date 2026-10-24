package ru.cherdakmd.chrdkquests;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Прогресс заданий по событиям мира (1.4.161).
 *
 * <p>Считаются только действия игрока: убийство с убийцей-игроком и сломанный
 * блок в выживании. Мобы со спавнера, из яйца и созданные командой дают
 * прогресс так же, как и обычные: задание — это «убей зомби», а не «убей
 * зомби правильным способом». А вот творческий режим блоки не считает — иначе
 * задание «добудь 64 камня» закрывалось бы в один клик.</p>
 *
 * <p>Сообщение о готовности показывается один раз на задание: иначе экшен-бар
 * мигал бы на каждом ударе после достижения цели.</p>
 */
public final class QuestsListener implements Listener {

    /** Сколько помним, что о готовности задания уже сообщили: сутки. */
    private static final long NOTIFY_LIFETIME_MS = 24L * 60L * 60L * 1_000L;

    private final QuestsPlugin plugin;
    /** Пары «игрок|задание», о готовности которых уже сообщили. */
    private final Map<String, Long> notified = new HashMap<>();

    QuestsListener(QuestsPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------------------------- мобы ---------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        handleKill(killer, event.getEntityType());
    }

    private void handleKill(Player player, EntityType type) {
        if (!plugin.config().enabled()) {
            return;
        }
        boolean changed = false;
        for (Quest quest : active(player)) {
            if (quest.type() != QuestType.KILL || !quest.matchesEntity(type)) {
                continue;
            }
            int before = plugin.store().progress(player.getUniqueId(), quest.id());
            int progress = plugin.store().addProgress(player.getUniqueId(), quest.id(), 1,
                    quest.amount());
            changed |= progress != before;
            if (progress >= quest.amount()) {
                announceReady(player, quest);
            }
        }
        if (changed) {
            plugin.requestSave();
        }
    }

    /* ---------------------------------- блоки --------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!plugin.config().enabled() || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Material type = event.getBlock().getType();
        boolean changed = false;
        for (Quest quest : active(player)) {
            if (quest.type() != QuestType.MINE || !quest.matchesMaterial(type)) {
                continue;
            }
            int before = plugin.store().progress(player.getUniqueId(), quest.id());
            int progress = plugin.store().addProgress(player.getUniqueId(), quest.id(), 1,
                    quest.amount());
            changed |= progress != before;
            if (progress >= quest.amount()) {
                announceReady(player, quest);
            }
        }
        if (changed) {
            plugin.requestSave();
        }
    }

    /* ---------------------------------- вход ---------------------------------- */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            // Ник нужен админским командам: игрок мог сменить его вне сервера.
            plugin.store().rename(player.getUniqueId(), player.getName());
            plugin.requestSave();
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    /**
     * Задания, прогресс которых сейчас честно двигать: первое незакрытое у
     * каждого персонажа, кроме чужих по фракции и слишком высоких по рангу.
     * Иначе игрок без фракции «дозарабатывал» бы задание, к которому ещё не
     * допущен, и сдача выглядела бы подарком.
     */
    private List<Quest> active(Player player) {
        List<Quest> out = new ArrayList<>();
        for (Quest quest : plugin.service().currentQuests(player.getUniqueId())) {
            QuestService.Availability availability =
                    plugin.service().availability(player.getUniqueId(), quest);
            if (availability == QuestService.Availability.WRONG_FACTION
                    || availability == QuestService.Availability.RANK
                    || availability == QuestService.Availability.LOCKED) {
                continue;
            }
            out.add(quest);
        }
        return out;
    }

    /** Сообщает о готовности ровно один раз за задание. */
    private void announceReady(Player player, Quest quest) {
        if (player == null || quest == null) {
            return;
        }
        String key = player.getUniqueId() + "|" + quest.id();
        long now = System.currentTimeMillis();
        Long seen = notified.get(key);
        if (seen != null && now - seen < NOTIFY_LIFETIME_MS) {
            return;
        }
        notified.put(key, now);
        if (notified.size() > 1_024) {
            notified.entrySet().removeIf(entry -> now - entry.getValue() > NOTIFY_LIFETIME_MS);
        }
        if (!plugin.config().actionbar()) {
            return;
        }
        player.sendActionBar(plugin.messages().get("progress-ready",
                "name", quest.name(),
                "id", quest.id()));
    }
}
