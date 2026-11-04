package ru.cherdakmd.chrdkregister;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Единственный владелец «введите значение следующим сообщением в чат» для всех
 * GUI ядра (админ-панель модерации, редактор особых званий и будущие панели).
 *
 * <p>Раньше у каждой панели была своя карта ожиданий и свой обработчик
 * {@link AsyncChatEvent} на LOWEST. Если игрок начинал ввод в одной панели и не
 * завершал его, а потом открывал другую, одно сообщение обрабатывали оба
 * обработчика; «зависшее» ожидание молча глотало следующую фразу в чате.
 * Здесь ровно одно ожидание на UUID: новое перекрывает старое, у него есть
 * срок жизни, оно снимается при выходе игрока и при открытии любого инвентаря.</p>
 *
 * <p>Отмена события происходит на LOWEST; если сборка Paper не позволяет
 * отменить {@code AsyncChatEvent}, очищаются получатели — текст ввода не должен
 * уходить в общий чат. Обработчик значения всегда уходит на главный поток.</p>
 */
public final class GuiChatInput implements Listener {

    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private record Claim(String owner, long at, Consumer<String> apply) {
    }

    private final JavaPlugin plugin;
    private final long timeoutMs;
    private final Map<UUID, Claim> claims = new ConcurrentHashMap<>();

    public GuiChatInput(JavaPlugin plugin) {
        this(plugin, DEFAULT_TIMEOUT_MS);
    }

    GuiChatInput(JavaPlugin plugin, long timeoutMs) {
        this.plugin = plugin;
        this.timeoutMs = Math.max(1L, timeoutMs);
    }

    /** Начать ввод и закрыть меню; предыдущее ожидание этого игрока заменяется. */
    public void begin(Player viewer, String owner, Consumer<String> apply) {
        if (viewer == null || apply == null) return;
        begin(viewer.getUniqueId(), owner, apply);
        viewer.closeInventory();
    }

    /** Вариант без закрытия инвентаря: используется двухшаговым вводом и тестами. */
    public void begin(UUID uuid, String owner, Consumer<String> apply) {
        if (uuid == null || owner == null || apply == null) return;
        claims.put(uuid, new Claim(owner, System.currentTimeMillis(), apply));
    }

    /** Кто сейчас владеет вводом; {@code null} — никого. Просроченное сбрасывается. */
    public String owner(UUID uuid) {
        Claim claim = claims.get(uuid);
        if (claim == null) return null;
        if (expired(claim)) {
            claims.remove(uuid, claim);
            return null;
        }
        return claim.owner();
    }

    public void end(UUID uuid, String owner) {
        Claim claim = claims.get(uuid);
        if (claim != null && claim.owner().equals(owner)) claims.remove(uuid, claim);
    }

    public void clear(UUID uuid) {
        claims.remove(uuid);
    }

    /** Снять все ожидания одной панели (при отключении плагина). */
    public void clearOwner(String owner) {
        claims.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
    }

    public void clearAll() {
        claims.clear();
    }

    public int size() {
        return claims.size();
    }

    private boolean expired(Claim claim) {
        return System.currentTimeMillis() - claim.at() > timeoutMs;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Claim claim = claims.get(id);
        if (claim == null) return;
        if (expired(claim)) {
            claims.remove(id, claim);
            return; // просроченный ввод не глотает сообщение
        }
        claims.remove(id, claim);
        try {
            event.setCancelled(true);
        } catch (Throwable ignored) {
            try {
                event.viewers().clear();
            } catch (Throwable ignoredToo) {
                // Сборка не даёт ни отменить, ни очистить получателей.
            }
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) claim.apply().accept(text);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        claims.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        // Игрок ушёл в другое меню, не ответив: ожидание больше не должно
        // перехватывать его следующее сообщение.
        if (event.getPlayer() instanceof Player player) claims.remove(player.getUniqueId());
    }
}
