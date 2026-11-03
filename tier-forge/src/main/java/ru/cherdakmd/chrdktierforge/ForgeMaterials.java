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
    private final Random random = new Random();
    private final ru.cherdakmd.chrdkregister.Messages legacy;
    ForgeMaterials(TierForgePlugin plugin) {
        this.plugin = plugin;
        token = new NamespacedKey(plugin, "forge_material_v1");
        sequence = new NamespacedKey(plugin, "forge_sequence");
        uid = new NamespacedKey(plugin, TierItems.PDC_MODIFIER_UID);
        try (var input=plugin.getResource("tierforge-messages-legacy-1.4.282-rc9.yml")) {
            legacy=ru.cherdakmd.chrdkregister.Messages.fromChecked(input,null,plugin.getLogger());
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException failure) { throw new IllegalStateException("Повреждён stock-снимок расходников",failure); }
        scheduleRefresh();
    }
    private void scheduleRefresh() {
        org.bukkit.Bukkit.getScheduler().runTask(plugin,() -> org.bukkit.Bukkit.getOnlinePlayers().forEach(this::refresh));
    }
    private void refresh(Player player) {
        for (var stack:player.getInventory().getContents()) {
            var type=kind(stack); if (type==null) continue;
            var meta=stack.getItemMeta(); var before=meta.lore();
            if (before!=null && before.stream().map(net.kyori.adventure.text.Component::compact).toList().equals(
                    legacy.getList("forge."+type.id+".details").stream().map(net.kyori.adventure.text.Component::compact).toList())) {
                meta.lore(plugin.messages().getList("forge."+type.id+".details")); stack.setItemMeta(meta);
            }
        }
    }
    @EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent event) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin,() -> { if(event.getPlayer().isOnline()) refresh(event.getPlayer()); });
    }
    @EventHandler(ignoreCancelled=true) public void open(InventoryOpenEvent event) {
        if (event.getView() instanceof org.bukkit.inventory.view.AnvilView && event.getPlayer() instanceof Player player) refresh(player);
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
    record Plan(ItemStack left, ItemStack right, ItemStack result, int units, int xp, TierForgeConfig settings) { }
    static final class Failure extends RuntimeException {
        final String key; final Object[] arguments;
        Failure(String key, Object... arguments) { this.key=key; this.arguments=arguments; }
    }
    private void fail(String key,Object... arguments) { throw new Failure(key,arguments); }
    boolean command(Player player, String[] args) {
        plugin.messages().sendList(player,"forge-help");
        for (var kind : ForgeMaterial.values()) {
            player.sendMessage(plugin.messages().get("forge."+kind.id+".name"));
            plugin.messages().sendList(player,"forge."+kind.id+".details");
        }
        return true; // Справка по старому адресу; применение только в настоящей наковальне.
    }
    Plan build(ItemStack main, ItemStack off, ForgeMaterial kind, String selectedId) {
        String tierId = plugin.items().tierOf(main);
        var tier = tierId == null ? null : plugin.config().find(tierId);
        var itemKind = ItemKind.classify(main.getType().name());
        if (main.getAmount() != 1 || tier == null || itemKind == null || !plugin.config().kindAllowed(itemKind)
                || plugin.items().isUnidentified(main) || kind(off) != kind || main.getItemMeta() == null) {
            fail("forge-input"); return null;
        }
        String identity = main.getItemMeta().getPersistentDataContainer().get(uid, PersistentDataType.STRING);
        long oldSequence = main.getItemMeta().getPersistentDataContainer().getOrDefault(sequence, PersistentDataType.LONG, 0L);
        if (!TierModifierIds.validUid(identity) || oldSequence < 0 || oldSequence == Long.MAX_VALUE) {
            fail("forge-legacy"); return null;
        }
        long next = ForgeMaterial.nextSequence(oldSequence);
        // Повтор предпросмотра, перестановка и relog не меняют выбор свойства; UID не перезаписывается.
        Random choice = ForgeMaterial.choice(identity, next, kind, selectedId == null ? "" : selectedId);
        var properties = new ArrayList<>(plugin.items().storedPropertiesOf(main));
        ForgeUpgrade.Def upgrade = null;
        int units = 1, xp;
        if (kind == ForgeMaterial.ASCEND) {
            String nextTier = TierDef.next(tierId);
            tier = nextTier == null ? null : plugin.config().find(nextTier);
            if (tier == null) { fail("up-max"); return null; }
            int index = TierDef.indexOf(nextTier);
            units = ForgeMaterial.ascendCost(index); xp = ForgeMaterial.ascendXp(index);
            if (!plugin.config().propertiesEnabled()) { fail("forge-unavailable"); return null; }
            properties = new ArrayList<>(TierProperties.roll(nextTier, itemKind,
                    plugin.config().propertiesPerItem(), choice));
        } else if (kind == ForgeMaterial.TEMPER) {
            upgrade = ForgeUpgrade.ALL.stream().filter(def -> def.id().equalsIgnoreCase(selectedId)).findFirst().orElse(null);
            int current = upgrade == null ? 0 : plugin.items().upgradeLevel(main, upgrade.id());
            if (!plugin.config().improveEnabled() || upgrade == null || !upgrade.fits(itemKind) || current < 1 || current >= upgrade.maxLevel()) {
                fail("forge-upgrade-invalid"); return null;
            }
            xp = 8 + current * 2;
        } else {
            TierProperty selected = TierProperties.byId(selectedId);
            if (!plugin.config().propertiesEnabled() || selected == null || !plugin.items().propertiesOf(main).contains(selected)) {
                fail("forge-property-invalid"); return null;
            }
            var excluded = Set.copyOf(properties);
            var alternatives = TierProperties.candidates(tierId, itemKind).stream().filter(p -> !excluded.contains(p)).toList();
            if (alternatives.isEmpty()) { fail("forge-property-invalid"); return null; }
            properties.set(properties.indexOf(selected), alternatives.get(choice.nextInt(alternatives.size())));
            xp = 12 + TierDef.indexOf(tierId) * 3;
        }
        if (off.getAmount() < units) fail("forge-cost", "units", units, "xp", xp);
        ItemStack result = plugin.items().forgeCopy(main, tier, properties, upgrade, next);
        return new Plan(main.clone(),off.clone(),result,units,xp,plugin.config());
    }
    List<String> complete(CommandSender sender,String[] args) { return args.length==2 && "help".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("help") : List.of(); }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEvent event) { if (kind(event.getItem()) != null && (event.getClickedBlock()==null || !org.bukkit.Tag.ANVIL.isTagged(event.getClickedBlock().getType()))) event.setCancelled(true); }
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
