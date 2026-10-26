package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Обязательный выбор профессии (1.4.182).
 *
 * <p>Без ремесла не идёт ни опыт, ни прибавки: игрок просто не видит системы.
 * Поэтому меню открывается само после входа и переоткрывается, если его
 * закрыли без выбора, а в экшен-баре висит напоминание. Играть это не мешает:
 * блоки, команды и награды остаются доступны — жёсткая блокировка ломает
 * первый вход, если игрок отошёл от клавиатуры.</p>
 *
 * <p>Открытие не перебивает чужие окна: пока игрок в сундуке, верстаке или
 * меню класса, попытка откладывается на десять секунд. Так класс и профессия
 * не борются за один экран — кто открылся первым, того и очередь.</p>
 */
final class ProfessionOnboarding implements Listener {

    /** Если игрок занят другим окном — не навязываемся, ждём десять секунд. */
    private static final long BUSY_RETRY_TICKS = 200L;

    private final ProfessionsPlugin plugin;
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private BukkitTask reminder;

    ProfessionOnboarding(ProfessionsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Запускает напоминание под текущий конфиг; повторный вызов перезапускает. */
    void start() {
        stopReminder();
        ProfessionConfig.Onboarding settings = settings();
        if (!settings.required() || settings.remindTicks() <= 0L) {
            return;
        }
        long period = settings.remindTicks();
        this.reminder = Bukkit.getScheduler().runTaskTimer(plugin, this::remindAll,
                period, period);
    }

    /** Останавливает задачи: вызывается при выключении и перезагрузке конфига. */
    void stop() {
        stopReminder();
        for (BukkitTask task : pending.values()) {
            task.cancel();
        }
        pending.clear();
    }

    private void stopReminder() {
        if (reminder != null) {
            reminder.cancel();
            reminder = null;
        }
    }

    private ProfessionConfig.Onboarding settings() {
        ProfessionConfig config = plugin.config();
        return config == null ? ProfessionConfig.Onboarding.DEFAULT : config.onboarding();
    }

    /* --------------------------------- события -------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!needed(player)) {
            return;
        }
        plugin.messages().send(player, "onboarding-start");
        schedule(player, settings().openTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    /**
     * Закрытие меню без выбора: напоминаем, что ремесло всё ещё нужно, и
     * переоткрываем. Если профессия уже выбрана — молчим.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ProfessionsGui.Holder)) {
            return;
        }
        if (!needed(player)) {
            return;
        }
        plugin.messages().send(player, "onboarding-closed");
        schedule(player, settings().reopenTicks());
    }

    /* -------------------------------- служебное -------------------------------- */

    /** Нужен ли игроку выбор: модуль включён, выбор обязателен, ремесла нет. */
    private boolean needed(Player player) {
        return player != null && player.isOnline()
                && settings().required()
                && plugin.config() != null && plugin.config().enabled()
                && plugin.service() != null
                && plugin.service().active(player.getUniqueId()) == null;
    }

    private void schedule(Player player, long delayTicks) {
        cancel(player.getUniqueId());
        long delay = Math.max(1L, delayTicks);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            open(player);
        }, delay);
        pending.put(player.getUniqueId(), task);
    }

    private void cancel(UUID id) {
        BukkitTask task = pending.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /** Открывает меню, если игрок не занят другим интерфейсом. */
    private void open(Player player) {
        if (!needed(player) || player.isDead()) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
            schedule(player, BUSY_RETRY_TICKS);
            return;
        }
        plugin.gui().open(player);
    }

    private void remindAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!needed(player)) {
                continue;
            }
            player.sendActionBar(plugin.messages().get("onboarding-remind"));
        }
    }
}
