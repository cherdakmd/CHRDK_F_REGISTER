package ru.cherdakmd.chrdkteleports;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Кровать игрока — его дом и точка возрождения.
 *
 * <p>Когда игрок ложится спать, сервер записывает кровать в его точки дома под
 * именем {@code bed}: бесплатно, без права {@code chrdktele.homes.plus} и без
 * расхода слота из лимита. После этого {@code /home bed} работает как любая
 * другая точка, а смерть возвращает игрока к кровати — <b>даже если кровать
 * сломали</b>, потому что координаты записаны в {@code teleports-data.json}, а
 * не только в ванильной памяти игрока.</p>
 *
 * <p>Почему не полагаемся на ванильный respawn: ванильная привязка исчезает
 * вместе с блоком кровати, и тогда ядро (случайный спавн) уводит игрока в
 * случайную точку мира. Модуль же помнит место и возвращает игрока туда, а
 * если кровать застроили — ищет свободную клетку в пределах одного блока.</p>
 */
public final class BedHomeListener implements Listener {

    private final TeleportsPlugin plugin;

    public BedHomeListener(TeleportsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Игрок лёг спать — запоминаем кровать как точку дома и место возрождения. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        if (!plugin.config().bedSpawnEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        Block bed = event.getBed();
        if (bed == null || !plugin.config().worldAllowed(bed.getWorld().getName())) {
            return;
        }
        HomeLocation home = BedHome.at(bed, player.getLocation().getYaw());
        if (home == null) {
            return;
        }
        HomeStore store = plugin.store();
        HomeLocation previous = store.get(player.getUniqueId(), HomeStore.BED);
        // Не дёргаем файл и не спамим сообщением, если игрок лёг в ту же кровать.
        if (previous != null && sameSpot(previous, home)) {
            return;
        }
        store.put(player.getUniqueId(), home);
        plugin.messages().send(player, "home.bed-saved");
    }

    /**
     * Игрок зашёл впервые после установки модуля: если ванильная точка
     * возрождения у него уже стоит на кровати, переносим её в точки дома молча —
     * без сна и без сообщения. Так {@code /home bed} работает у старожилов
     * сразу, а не после следующей ночи.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().bedSpawnEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        HomeStore store = plugin.store();
        if (store.get(player.getUniqueId(), HomeStore.BED) != null) {
            return;
        }
        // Один раз на игрока: если чанк с кроватью ещё не загружен, сервер его
        // подгрузит, чтобы проверить блок. Дальше точка живёт в файле.
        Location respawn = player.getRespawnLocation();
        if (respawn == null || respawn.getWorld() == null) {
            return;
        }
        if (!plugin.config().worldAllowed(respawn.getWorld().getName())) {
            return;
        }
        Block block = respawn.getBlock();
        if (!Tag.BEDS.isTagged(block.getType())) {
            return;
        }
        HomeLocation home = BedHome.at(block, player.getLocation().getYaw());
        if (home != null) {
            store.put(player.getUniqueId(), home);
        }
    }

    /**
     * Возрождение после смерти: если у игрока есть запомненная кровать — он
     * появляется у неё. Ставим на HIGHEST, чтобы решение ядра о случайной точке
     * было уже принято и перекрыто явной волей игрока.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }
        if (!plugin.config().bedSpawnEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        HomeLocation home = plugin.store().get(player.getUniqueId(), HomeStore.BED);
        if (home == null) {
            return;
        }
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            return;
        }
        Location spot = BedHome.safeSpot(world, home);
        if (spot == null) {
            return;
        }
        event.setRespawnLocation(spot);
    }

    /** Та же клетка и тот же мир: повторный сон не перезаписывает файл. */
    private static boolean sameSpot(HomeLocation first, HomeLocation second) {
        return first.world().equals(second.world())
                && Math.abs(first.x() - second.x()) < 0.01
                && Math.abs(first.y() - second.y()) < 0.01
                && Math.abs(first.z() - second.z()) < 0.01;
    }
}
