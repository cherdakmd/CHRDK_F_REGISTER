package ru.cherdakmd.chrdkclasses;

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
 * Обязательный выбор класса (1.4.182).
 *
 * <p>Игрок без класса не получает ничего: умения, таланты и прибавки открыты
 * только выбравшим. Раньше это было неочевидно — новичок бегал без стиля игры и
 * не понимал, почему «ничего не работает». Теперь меню открывается само после
 * входа и переоткрывается, если его закрыли без выбора, а в экшен-баре висит
 * напоминание.</p>
 *
 * <p>Геймплей при этом не блокируется: нельзя ломать игру и превращать первый
 * вход в запирание. Если игрок в другом окне (сундук, верстак, чужое меню),
 * открытие откладывается — перебивать чужой интерфейс невежливо и опасно.
 * После десяти секунд ожидания попытка повторяется.</p>
 */
final class ClassOnboarding implements Listener {

    /** Если игрок занят другим окном — не навязываемся, ждём десять секунд. */
    private static final long BUSY_RETRY_TICKS = 200L;

    private final ClassesPlugin plugin;
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private BukkitTask reminder;

    ClassOnboarding(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Запускает напоминание под текущий конфиг; повторный вызов перезапускает. */
    void start() {
        stopReminder();
        ClassConfig.Onboarding settings = settings();
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

    private ClassConfig.Onboarding settings() {
        ClassConfig config = plugin.config();
        return config == null ? ClassConfig.Onboarding.DEFAULT : config.onboarding();
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
     * Закрытие меню без выбора: говорим, что класс всё ещё нужен, и
     * переоткрываем. Если класс уже выбран — молчим: выбор сделан в этом меню.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ClassesGui.Holder)
                && !(event.getInventory().getHolder() instanceof DevelopmentGui.Holder)) {
            return;
        }
        if (!needed(player)) {
            return;
        }
        plugin.messages().send(player, "onboarding-closed");
        schedule(player, settings().reopenTicks());
    }

    /* -------------------------------- служебное -------------------------------- */

    /** Нужен ли игроку выбор: классы включены, выбор обязателен, класса нет. */
    private boolean needed(Player player) {
        return player != null && player.isOnline()
                && settings().required()
                && plugin.config() != null && plugin.config().enabled()
                && plugin.service() != null
                && plugin.service().classOf(player.getUniqueId()) == null;
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
        if (!plugin.ready(player)) {schedule(player, BUSY_RETRY_TICKS); return;}
        plugin.gui().open(player);
    }

    private void remindAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!needed(player) || plugin.coreApi()==null || !plugin.coreApi().isAuthenticated(player)) {
                continue;
            }
            player.sendActionBar(plugin.messages().get("onboarding-remind"));
        }
    }
}
