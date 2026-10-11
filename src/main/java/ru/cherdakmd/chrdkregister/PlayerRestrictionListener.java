package ru.cherdakmd.chrdkregister;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.Locale;
import java.util.Set;

/**
 * Blocks all world, movement, inventory, combat, chat, and command actions
 * until the player has an active pass and has completed password + TOTP 2FA authentication.
 */
public final class PlayerRestrictionListener implements Listener {
    private static final Set<String> PASS_COMMANDS = Set.of("pass", "fregister", "prohodka");
    private static final Set<String> REGISTER_COMMANDS = Set.of("register", "reg", "pass", "fregister", "prohodka");
    private static final Set<String> LOGIN_COMMANDS = Set.of("login", "l", "pass", "fregister", "prohodka");
    private static final Set<String> TWO_FACTOR_COMMANDS =
            Set.of("2fa", "totp", "twofactor", "login", "l", "pass", "fregister", "prohodka");

    private final AccessManager accessManager;

    public PlayerRestrictionListener(AccessManager accessManager) {
        this.accessManager = accessManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        accessManager.onPlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        accessManager.onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (accessManager.isBlocked(player)) {
            accessManager.updateFreezeLocation(player, event.getRespawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!accessManager.isBlocked(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getWorld() != to.getWorld()
                || Double.compare(from.getX(), to.getX()) != 0
                || Double.compare(from.getY(), to.getY()) != 0
                || Double.compare(from.getZ(), to.getZ()) != 0) {
            Location locked = from.clone();
            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());
            event.setTo(locked);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        AccessManager.PlayerStage stage = accessManager.getStage(player);
        if (stage == AccessManager.PlayerStage.AUTHENTICATED) return;

        String label = extractCommandLabel(event.getMessage());
        if (isCommandAllowed(stage, label)) {
            return;
        }

        event.setCancelled(true);
        switch (stage) {
            case NO_PASS -> player.sendMessage(Component.text(
                    "Команды заблокированы! Сначала приобретите проходку (150₽ на DonatePay, укажите ник "
                            + player.getName() + " в имени донатера).",
                    NamedTextColor.RED));
            case NEEDS_REGISTER -> player.sendMessage(Component.text(
                    "Сначала зарегистрируйте пароль: /register <пароль> <повтор>",
                    NamedTextColor.RED));
            case NEEDS_LOGIN -> player.sendMessage(Component.text(
                    "Сначала войдите в аккаунт: /login <пароль>",
                    NamedTextColor.RED));
            case NEEDS_2FA -> player.sendMessage(Component.text(
                    "Подтвердите вход кодом двухфакторной аутентификации: /2fa <6-значный_код>",
                    NamedTextColor.RED));
            default -> {
            }
        }
    }

    public static String extractCommandLabel(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return "";
        String trimmed = rawMessage.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        int spaceIndex = trimmed.indexOf(' ');
        String command = (spaceIndex >= 0 ? trimmed.substring(0, spaceIndex) : trimmed).toLowerCase(Locale.ROOT);
        int colonIndex = command.lastIndexOf(':');
        if (colonIndex >= 0 && colonIndex + 1 < command.length()) {
            command = command.substring(colonIndex + 1);
        }
        return command;
    }

    public static boolean isCommandAllowed(AccessManager.PlayerStage stage, String label) {
        if (label == null || label.isEmpty()) return false;
        return switch (stage) {
            case AUTHENTICATED -> true;
            case NO_PASS -> PASS_COMMANDS.contains(label);
            case NEEDS_REGISTER -> REGISTER_COMMANDS.contains(label);
            case NEEDS_LOGIN -> LOGIN_COMMANDS.contains(label);
            case NEEDS_2FA -> TWO_FACTOR_COMMANDS.contains(label);
        };
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        AccessManager.PlayerStage stage = accessManager.getStage(player);
        if (stage == AccessManager.PlayerStage.AUTHENTICATED) return;

        event.setCancelled(true);
        switch (stage) {
            case NO_PASS -> player.sendMessage(Component.text(
                    "Чат заблокирован до покупки проходки на DonatePay!",
                    NamedTextColor.RED));
            case NEEDS_REGISTER -> player.sendMessage(Component.text(
                    "Чат заблокирован! Зарегистрируйтесь: /register <пароль> <повтор>",
                    NamedTextColor.RED));
            case NEEDS_LOGIN -> player.sendMessage(Component.text(
                    "Чат заблокирован! Авторизуйтесь: /login <пароль>",
                    NamedTextColor.RED));
            case NEEDS_2FA -> player.sendMessage(Component.text(
                    "Чат заблокирован! Подтвердите вход кодом 2FA: /2fa <6-значный_код>",
                    NamedTextColor.RED));
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim && accessManager.isBlocked(victim)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Player attacker && accessManager.isBlocked(attacker)) {
                event.setCancelled(true);
                return;
            }
            if (byEntity.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player shooter
                    && accessManager.isBlocked(shooter)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAirChange(EntityAirChangeEvent event) {
        if (event.getEntity() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (accessManager.isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && accessManager.isBlocked(player)) {
            event.setCancelled(true);
        }
    }
}
