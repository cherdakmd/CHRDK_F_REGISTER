package ru.cherdakmd.chrdktierforge;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.events.QualifiedHuntKillEvent;

import java.util.*;

/** Главный поток: read-only предпросмотр → одноразовое подтверждение неизменных входов.
 * Предметы/XP принадлежат обычному playerdata; это не финансовый ledger и не crash-atomic БД. */
final class ForgeMaterials implements Listener {
    private final TierForgePlugin plugin;
    private final NamespacedKey token;
    private final NamespacedKey sequence;
    private final NamespacedKey uid;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Random random = new Random();
    private record Pending(ItemStack main, ItemStack off, ItemStack result, int slot, int units, int xp,
                           UUID world, TierForgeConfig settings, long deadline) { }
    ForgeMaterials(TierForgePlugin plugin) {
        this.plugin = plugin;
        token = new NamespacedKey(plugin, "forge_material_v1");
        sequence = new NamespacedKey(plugin, "forge_sequence");
        uid = new NamespacedKey(plugin, TierItems.PDC_MODIFIER_UID);
    }
    boolean enabled() { return plugin.getConfig().getBoolean("forge-materials.enabled", true); }
    boolean legacyRerollEnabled() {
        return plugin.config().rerollEnabled() && !(enabled() && plugin.getConfig().getBoolean("forge-materials.replace-amethyst-reroll", true));
    }
    ForgeMaterial kind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var kind = ForgeMaterial.byId(item.getItemMeta().getPersistentDataContainer().get(token, PersistentDataType.STRING));
        return kind != null && item.getType().name().equals(kind.material) ? kind : null;
    }
    ItemStack item(ForgeMaterial kind, int amount) {
        ItemStack item = new ItemStack(Material.valueOf(kind.material), Math.clamp(amount, 1, 64));
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(token, PersistentDataType.STRING, kind.id);
        meta.displayName(plugin.messages().get("forge." + kind.id + ".name"));
        meta.lore(plugin.messages().getList("forge." + kind.id + ".details"));
        item.setItemMeta(meta);
        return item;
    }
    @EventHandler public void reward(QualifiedHuntKillEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || !player.isOnline() || player.getGameMode() != GameMode.SURVIVAL
                || !player.hasPermission("chrdktierforge.use") || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        for (var kind : ForgeMaterial.values()) {
            double base = plugin.getConfig().getDouble("forge-materials.drop-percent." + kind.id,
                    kind == ForgeMaterial.ASCEND ? 1.0 : kind == ForgeMaterial.TEMPER ? .7 : .35);
            if (random.nextDouble(100) >= ForgeMaterial.chance(base, event.level(), event.elite())) continue;
            player.getInventory().addItem(item(kind, 1)).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            plugin.messages().send(player, "forge-found", "name", plugin.messages().mini("forge." + kind.id + ".name"));
        }
    }
    boolean command(Player player, String[] args) {
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("help")) {
            plugin.messages().sendList(player, "forge-help");
            for (var kind : ForgeMaterial.values()) {
                player.sendMessage(plugin.messages().get("forge." + kind.id + ".name"));
                plugin.messages().sendList(player, "forge." + kind.id + ".details");
            }
            return true;
        }
        if (!enabled() || !plugin.config().anvilEnabled() || player.getGameMode() != GameMode.SURVIVAL
                || !player.hasPermission("chrdktierforge.use") || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)
                || player.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            pending.remove(player.getUniqueId()); plugin.messages().send(player, "forge-unavailable"); return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            var plan = pending.remove(player.getUniqueId());
            var inventory = player.getInventory();
            if (plan == null || !new ForgeQuote<>(plan.main, plan.off, plan.slot, plan.world, plan.settings, plan.deadline, plan.xp)
                    .matches(inventory.getItemInMainHand(), inventory.getItemInOffHand(), inventory.getHeldItemSlot(),
                            player.getWorld().getUID(), plugin.config(), System.currentTimeMillis(), player.getLevel())) {
                plugin.messages().send(player, "forge-stale"); return true;
            }
            // План собран полностью до изменения входов, событий/сети/асинхронного ожидания между записями нет.
            ItemStack rest = plan.off.clone(); rest.setAmount(rest.getAmount() - plan.units);
            int level = player.getLevel(), total = player.getTotalExperience();
            float progress = player.getExp();
            try {
                inventory.setItemInOffHand(rest.getAmount() == 0 ? null : rest);
                inventory.setItemInMainHand(plan.result.clone());
                player.giveExpLevels(-plan.xp);
            } catch (RuntimeException failure) {
                inventory.setItemInMainHand(plan.main.clone());
                inventory.setItemInOffHand(plan.off.clone());
                player.setLevel(level); player.setTotalExperience(total); player.setExp(progress);
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Кузня: откат незавершённого применения", failure);
                plugin.messages().send(player, "forge-stale"); return true;
            }
            plugin.messages().send(player, "forge-done");
            plugin.collection().record(player, plan.result);
            return true;
        }
        pending.remove(player.getUniqueId());
        ForgeMaterial kind = ForgeMaterial.byId(args[1]);
        if (kind == null || args.length != (kind == ForgeMaterial.ASCEND ? 2 : 3)) {
            plugin.messages().send(player, "forge-usage"); return true;
        }
        var main = player.getInventory().getItemInMainHand();
        var off = player.getInventory().getItemInOffHand();
        String tierId = plugin.items().tierOf(main);
        var tier = tierId == null ? null : plugin.config().find(tierId);
        var itemKind = ItemKind.classify(main.getType().name());
        if (main.getAmount() != 1 || tier == null || itemKind == null || !plugin.config().kindAllowed(itemKind)
                || plugin.items().isUnidentified(main) || kind(off) != kind || main.getItemMeta() == null) {
            plugin.messages().send(player, "forge-input"); return true;
        }
        String identity = main.getItemMeta().getPersistentDataContainer().get(uid, PersistentDataType.STRING);
        long oldSequence = main.getItemMeta().getPersistentDataContainer().getOrDefault(sequence, PersistentDataType.LONG, 0L);
        if (!TierModifierIds.validUid(identity) || oldSequence < 0 || oldSequence == Long.MAX_VALUE) {
            plugin.messages().send(player, "forge-legacy"); return true;
        }
        long next = ForgeMaterial.nextSequence(oldSequence);
        // Повтор предпросмотра, перестановка и relog не меняют выбор свойства; UID не перезаписывается.
        Random choice = new Random(Objects.hash(identity, next, kind.id, args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : ""));
        var properties = new ArrayList<>(plugin.items().storedPropertiesOf(main));
        ForgeUpgrade.Def upgrade = null;
        int units = 1, xp;
        if (kind == ForgeMaterial.ASCEND) {
            String nextTier = TierDef.next(tierId);
            tier = nextTier == null ? null : plugin.config().find(nextTier);
            if (tier == null) { plugin.messages().send(player, "up-max"); return true; }
            int index = TierDef.indexOf(nextTier);
            units = ForgeMaterial.ascendCost(index); xp = ForgeMaterial.ascendXp(index);
            if (!plugin.config().propertiesEnabled()) { plugin.messages().send(player, "forge-unavailable"); return true; }
            properties = new ArrayList<>(TierProperties.roll(nextTier, itemKind,
                    plugin.config().propertiesPerItem(), choice));
        } else if (kind == ForgeMaterial.TEMPER) {
            upgrade = ForgeUpgrade.ALL.stream().filter(def -> def.id().equalsIgnoreCase(args[2])).findFirst().orElse(null);
            int current = upgrade == null ? 0 : plugin.items().upgradeLevel(main, upgrade.id());
            if (!plugin.config().improveEnabled() || upgrade == null || !upgrade.fits(itemKind) || current < 1 || current >= upgrade.maxLevel()) {
                plugin.messages().send(player, "forge-upgrade-invalid"); return true;
            }
            xp = 8 + current * 2;
        } else {
            TierProperty selected = TierProperties.byId(args[2]);
            if (!plugin.config().propertiesEnabled() || selected == null || !plugin.items().propertiesOf(main).contains(selected)) {
                plugin.messages().send(player, "forge-property-invalid"); return true;
            }
            var excluded = Set.copyOf(properties);
            var alternatives = TierProperties.candidates(tierId, itemKind).stream().filter(p -> !excluded.contains(p)).toList();
            if (alternatives.isEmpty()) { plugin.messages().send(player, "forge-property-invalid"); return true; }
            properties.set(properties.indexOf(selected), alternatives.get(choice.nextInt(alternatives.size())));
            xp = 12 + TierDef.indexOf(tierId) * 3;
        }
        if (off.getAmount() < units || player.getLevel() < xp) {
            plugin.messages().send(player, "forge-cost", "units", units, "xp", xp); return true;
        }
        ItemStack result = plugin.items().forgeCopy(main, tier, properties, upgrade, next);
        // Одна запись на игрока, очищается на quit/open; никакой выдачи при просмотре.
        pending.put(player.getUniqueId(), new Pending(main.clone(), off.clone(), result, player.getInventory().getHeldItemSlot(),
                units, xp, player.getWorld().getUID(), plugin.config(), System.currentTimeMillis() + 30_000));
        plugin.messages().send(player, "forge-preview", "units", units, "xp", xp);
        if (result.getItemMeta().displayName() != null) player.sendMessage(result.getItemMeta().displayName());
        if (result.getItemMeta().lore() != null) result.getItemMeta().lore().forEach(player::sendMessage);
        plugin.messages().send(player, "forge-preserve");
    return true;
    }
    List<String> complete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(p)
                || !p.hasPermission("chrdktierforge.use")) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = args.length == 2 ? List.of("help", "ascend", "temper", "reforge", "confirm")
                : args.length == 3 && args[1].equalsIgnoreCase("temper") ? plugin.items().upgradesOf(p.getInventory().getItemInMainHand()).keySet().stream().map(ForgeUpgrade.Def::id).toList()
                : args.length == 3 && args[1].equalsIgnoreCase("reforge") ? plugin.items().propertiesOf(p.getInventory().getItemInMainHand()).stream().map(TierProperty::id).toList() : List.of();
        return values.stream().filter(s -> s.startsWith(prefix)).toList();
    }
    @EventHandler public void quit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void open(InventoryOpenEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEvent event) { if (kind(event.getItem()) != null) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void place(BlockPlaceEvent event) { if (kind(event.getItemInHand()) != null) event.setCancelled(true); }
    @EventHandler public void craft(PrepareItemCraftEvent event) {
        if (Arrays.stream(event.getInventory().getMatrix()).anyMatch(i -> kind(i) != null)) event.getInventory().setResult(null);
    }
    @EventHandler(ignoreCancelled = true) public void crafter(org.bukkit.event.block.CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter
                && Arrays.stream(crafter.getInventory().getContents()).anyMatch(item -> kind(item) != null)) event.setCancelled(true);
    }
    @EventHandler public void smith(PrepareSmithingEvent event) {
        for (int i = 0; i < 3; i++) if (kind(event.getInventory().getItem(i)) != null) { event.setResult(null); return; }
    }
}
