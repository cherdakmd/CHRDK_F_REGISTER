package ru.cherdakmd.chrdktierforge;

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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.ForgeMarketCatalog;

import java.util.*;
import java.util.function.Consumer;

/** Главный поток: read-only предпросмотр → одноразовое подтверждение неизменных входов.
 * Предметы/XP принадлежат обычному playerdata; это не финансовый ledger и не crash-atomic БД.
 * Восемь операций; расходники доступны через /shop forge, искра даёт первый тир. */
final class ForgeMaterials implements Listener {
    private final TierForgePlugin plugin;
    private final NamespacedKey token;
    private final NamespacedKey sequence;
    private final NamespacedKey uid;
    private final NamespacedKey seedUuid;
    private final NamespacedKey seedStack;
    private final List<ru.cherdakmd.chrdkregister.Messages> legacy;

    ForgeMaterials(TierForgePlugin plugin) {
        this.plugin = plugin;
        token = new NamespacedKey(plugin, ForgeMarketCatalog.TOKEN_KEY);
        sequence = new NamespacedKey(plugin, "forge_sequence");
        uid = new NamespacedKey(plugin, TierItems.PDC_MODIFIER_UID);
        seedUuid = ForgeMarketCatalog.seedUuidKey();
        seedStack = ForgeMarketCatalog.seedStackKey();
        List<ru.cherdakmd.chrdkregister.Messages> snapshots = new ArrayList<>();
        for (String resource : List.of("tierforge-messages-legacy-1.4.282-rc9.yml",
                "tierforge-messages-legacy-1.4.282-rc15.yml")) {
            try (var input = plugin.getResource(resource)) {
                if (input != null) snapshots.add(ru.cherdakmd.chrdkregister.Messages.fromChecked(input, null, plugin.getLogger()));
            } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
                throw new IllegalStateException("Повреждён stock-снимок расходников " + resource, failure);
            }
        }
        legacy = List.copyOf(snapshots);
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> org.bukkit.Bukkit.getOnlinePlayers().forEach(this::refresh));
    }

    /** Обновляем только точный прежний stock-лор; custom/empty overrides владельца не трогаем. */
    private void refresh(Player player) {
        for (var stack : player.getInventory().getContents()) {
            var type = kind(stack);
            if (type == null) continue;
            var meta = stack.getItemMeta();
            var before = meta.lore();
            if (before == null) continue;
            java.util.function.Function<net.kyori.adventure.text.Component, net.kyori.adventure.text.Component> compact =
                    net.kyori.adventure.text.Component::compact;
            for (var snapshot : legacy) {
                if (!snapshot.has("forge." + type.id + ".details")) continue;
                if (before.stream().map(compact).toList().equals(
                        snapshot.getList("forge." + type.id + ".details").stream().map(compact).toList())) {
                    meta.lore(plugin.messages().getList("forge." + type.id + ".details"));
                    stack.setItemMeta(meta);
                    break;
                }
            }
        }
    }

    @EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent event) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> { if (event.getPlayer().isOnline()) refresh(event.getPlayer()); });
    }
    @EventHandler(ignoreCancelled = true) public void open(InventoryOpenEvent event) {
        if (event.getView() instanceof org.bukkit.inventory.view.AnvilView && event.getPlayer() instanceof Player player) refresh(player);
    }
    boolean enabled() { return plugin.getConfig().getBoolean("forge-materials.enabled", true); }
    boolean operationEnabled(ForgeMaterial kind) {
        return enabled() && plugin.getConfig().getBoolean("forge-materials.operations." + kind.id, true);
    }
    boolean legacyRerollEnabled() {
        return plugin.config().rerollEnabled() && !(enabled() && plugin.getConfig().getBoolean("forge-materials.replace-amethyst-reroll", true));
    }

    ForgeMaterial kind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var kind = ForgeMaterial.byId(item.getItemMeta().getPersistentDataContainer().get(token, PersistentDataType.STRING));
        return kind != null && item.getType().name().equals(kind.material) ? kind : null;
    }

    /** Настоящий tagged PDC-прототип. Искра — строго 1 шт. с уникальным каноническим UUID. */
    ItemStack item(ForgeMaterial kind, int amount) {
        int count = kind.seed() ? 1 : Math.clamp(amount, 1, 64);
        ItemStack item = new ItemStack(Material.valueOf(kind.material), count);
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(token, PersistentDataType.STRING, kind.id);
        meta.displayName(plugin.messages().get("forge." + kind.id + ".name"));
        meta.lore(plugin.messages().getList("forge." + kind.id + ".details"));
        if (kind.seed()) {
            meta.getPersistentDataContainer().set(seedUuid, PersistentDataType.STRING, UUID.randomUUID().toString());
            meta.getPersistentDataContainer().set(seedStack, PersistentDataType.INTEGER, 1);
            setMaxStackOne(meta);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Paper/Purpur 26.3: компонент max_stack_size. Отражательный вызов не ломает старые сборки. */
    private static void setMaxStackOne(ItemMeta meta) {
        for (Class<?> parameter : new Class<?>[]{Integer.class, int.class}) {
            try {
                var method = ItemMeta.class.getMethod("setMaxStackSize", parameter);
                method.invoke(meta, parameter == int.class ? 1 : Integer.valueOf(1));
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }

    record Plan(ItemStack left, ItemStack right, ItemStack result, int units, int xp, TierForgeConfig settings) { }
    static final class Failure extends RuntimeException {
        final String key; final Object[] arguments;
        Failure(String key, Object... arguments) { this.key = key; this.arguments = arguments; }
    }
    private void fail(String key, Object... arguments) { throw new Failure(key, arguments); }

    boolean command(Player player, String[] args) {
        plugin.messages().sendList(player, "forge-help");
        for (var kind : ForgeMaterial.values()) {
            player.sendMessage(plugin.messages().get("forge." + kind.id + ".name"));
            plugin.messages().sendList(player, "forge." + kind.id + ".details");
        }
        return true; // Справка по старому адресу; применение только в настоящей наковальне.
    }

    /** Preview не меняет входные вещи: результат собирается из clone, оплата проверяется отдельно. */
    Plan build(ItemStack main, ItemStack off, ForgeMaterial kind, String selectedId) {
        if (main == null || off == null || kind == null || kind(off) != kind) {
            fail(ForgeInputDiagnostics.MISSING); return null;
        }
        var itemKind = ItemKind.classify(main.getType().name());
        String tierId = plugin.items().tierOf(main);
        // 7 ноября 2026: одна общая строка «нужна опознанная тир-вещь» скрывала
        // шесть разных причин, а для искры была прямо противоположной правде.
        String diagnostic = ForgeInputDiagnostics.of(main.getAmount(),
                plugin.items().isUnidentified(main), main.getItemMeta() != null,
                itemKind, plugin.config().kindAllowed(itemKind), kind.seed(), tierId != null);
        if (diagnostic != null) {
            fail(diagnostic, "kind", itemKind == null ? "?" : itemKind.name().toLowerCase(Locale.ROOT));
            return null;
        }
        if (!operationEnabled(kind)) { fail("forge-unavailable"); return null; }

        var tier = tierId == null ? null : plugin.config().find(tierId);
        if (kind.seed()) return seedPlan(main, off, kind, itemKind, tierId);
        if (tier == null) { fail(ForgeInputDiagnostics.NEED_TIER); return null; }

        String identity = main.getItemMeta().getPersistentDataContainer().get(uid, PersistentDataType.STRING);
        long oldSequence = main.getItemMeta().getPersistentDataContainer().getOrDefault(sequence, PersistentDataType.LONG, 0L);
        if (!TierModifierIds.validUid(identity) || oldSequence < 0 || oldSequence == Long.MAX_VALUE) {
            fail("forge-legacy"); return null;
        }
        long next = ForgeMaterial.nextSequence(oldSequence);
        Random choice = ForgeMaterial.choice(identity, next, kind, selectedId == null ? "" : selectedId);
        var properties = new ArrayList<>(plugin.items().storedPropertiesOf(main));
        ForgeUpgrade.Def upgrade = null;
        Consumer<ItemMeta> mutation = null;
        boolean rename = false;
        int units = 1, xp;
        // 1.5.2: при чаре megamine не выдаём ни «Карьер», ни «Забойщик».
        var enchantsApi = plugin.enchantsApi();
        boolean megaMining = enchantsApi != null
                && MiningCompatPolicy.hasMegaMining(enchantsApi.enchantsOf(main));

        if (kind == ForgeMaterial.ASCEND) {
            String nextTier = TierDef.next(tierId);
            tier = nextTier == null ? null : plugin.config().find(nextTier);
            if (tier == null) { fail("up-max"); return null; }
            int index = TierDef.indexOf(nextTier);
            units = ForgeMaterial.ascendCost(index); xp = ForgeMaterial.ascendXp(index);
            if (!plugin.config().propertiesEnabled()) { fail("forge-unavailable"); return null; }
            properties = new ArrayList<>(TierProperties.roll(nextTier, itemKind,
                    plugin.config().propertiesPerItem(), choice, MiningCompatPolicy.forbidden(megaMining)));
        } else if (kind == ForgeMaterial.TEMPER) {
            upgrade = ForgeUpgrade.byId(selectedId);
            int current = upgrade == null ? 0 : plugin.items().upgradeLevel(main, upgrade.id());
            if (!plugin.config().improveEnabled() || upgrade == null || !upgrade.fits(itemKind) || current < 1 || current >= upgrade.maxLevel()
                    || megaMining && MiningCompatPolicy.isColumnMiningUpgrade(upgrade.id())) {
                fail("forge-upgrade-invalid"); return null;
            }
            xp = 8 + current * 2;
        } else if (kind == ForgeMaterial.REFORGE) {
            TierProperty selected = TierProperties.byId(selectedId);
            if (!plugin.config().propertiesEnabled() || selected == null || !plugin.items().propertiesOf(main).contains(selected)) {
                fail("forge-property-invalid"); return null;
            }
            var excluded = Set.copyOf(properties);
            var alternatives = TierProperties.candidates(tierId, itemKind).stream().filter(p -> !excluded.contains(p)).toList();
            if (alternatives.isEmpty()) { fail("forge-property-invalid"); return null; }
            properties.set(properties.indexOf(selected), alternatives.get(choice.nextInt(alternatives.size())));
            xp = 12 + TierDef.indexOf(tierId) * 3;
        } else if (kind == ForgeMaterial.REPAIR) {
            if (!(main.getItemMeta() instanceof Damageable damageable) || !damageable.hasDamage()) {
                fail(ForgeInputDiagnostics.REPAIR_UNDAMAGED); return null;
            }
            mutation = meta -> { if (meta instanceof Damageable d) d.setDamage(0); };
            xp = ForgeMaterial.REPAIR_XP;
        } else if (kind == ForgeMaterial.INSCRIBE) {
            upgrade = ForgeUpgrade.byId(selectedId);
            if (!plugin.config().improveEnabled() || upgrade == null || !upgrade.fits(itemKind)
                    || plugin.items().upgradeLevel(main, upgrade.id()) != 0
                    || megaMining && MiningCompatPolicy.isColumnMiningUpgrade(upgrade.id())) {
                fail("forge-upgrade-invalid"); return null;
            }
            String id = upgrade.id();
            mutation = meta -> plugin.items().setUpgradeLevel(meta, id, 1);
            xp = ForgeMaterial.INSCRIBE_XP;
        } else if (kind == ForgeMaterial.REMOVE) {
            upgrade = ForgeUpgrade.byId(selectedId);
            if (upgrade == null || plugin.items().upgradeLevel(main, upgrade.id()) < 1) { fail("forge-upgrade-invalid"); return null; }
            String id = upgrade.id();
            mutation = meta -> plugin.items().setUpgradeLevel(meta, id, 0);
            xp = ForgeMaterial.REMOVE_XP;
        } else {
            // NAME: другая кузнечная кличка, без переролла чар.
            String current = plugin.items().forgeNameOf(main);
            String picked = anotherName(itemKind, current, choice);
            if (picked == null) { fail(ForgeInputDiagnostics.NAME_POOL_EMPTY); return null; }
            mutation = meta -> plugin.items().setForgeNickname(meta, picked);
            rename = true;
            xp = ForgeMaterial.NAME_XP;
        }
        if (off.getAmount() < units) fail("forge-cost", "units", units, "xp", xp);
        ItemStack result = plugin.items().forgeCopy(main, tier, properties, upgrade, next, mutation, null, rename);
        return new Plan(main.clone(), off.clone(), result, units, xp, plugin.config());
    }

    /** Только искра расширяет eligibility до вещи без тира; первая редкость — строго «Обычный». */
    private Plan seedPlan(ItemStack main, ItemStack off, ForgeMaterial kind, ItemKind itemKind, String tierId) {
        if (tierId != null) { fail(ForgeInputDiagnostics.SEED_TIERED); return null; }
        var tier = plugin.config().find("common");
        if (tier == null) { fail("forge-unavailable"); return null; }
        String canonical = off.getItemMeta().getPersistentDataContainer().get(seedUuid, PersistentDataType.STRING);
        if (!ForgeMarketCatalog.canonicalUuid(canonical)) {
            fail(ForgeInputDiagnostics.SEED_INVALID); return null;
        }
        // 36-символьный UUID НЕ передаётся в validUid: только производный 12-hex.
        String uidValue = ForgeMaterial.seedUid(canonical);
        if (!TierModifierIds.validUid(uidValue)) {
            fail(ForgeInputDiagnostics.SEED_UID); return null;
        }
        long next = ForgeMaterial.nextSequence(0L);
        Random choice = ForgeMaterial.choice(canonical, next, kind, "common");
        var properties = new ArrayList<>(TierProperties.roll("common", itemKind,
                plugin.config().propertiesPerItem(), choice));
        int xp = ForgeMaterial.SEED_XP;
        if (off.getAmount() < 1) fail("forge-cost", "units", 1, "xp", xp);
        ItemStack result = plugin.items().forgeMaterialCopy(main, tier, properties, uidValue, next, null, false);
        return new Plan(main.clone(), off.clone(), result, 1, xp, plugin.config());
    }

    /** Детерминированно выбираем имя, отличное от текущего; результат стабилен после relog. */
    private static String anotherName(ItemKind kind, String current, Random choice) {
        var group = ForgeNamePool.Group.of(kind);
        if (group == null) return null;
        List<String> pool = switch (group) {
            case WEAPONS -> ForgeNamePool.DEFAULT_WEAPONS;
            case ARMOR -> ForgeNamePool.DEFAULT_ARMOR;
            case TOOLS -> ForgeNamePool.DEFAULT_TOOLS;
            case UTILITIES -> ForgeNamePool.DEFAULT_UTILITIES;
        };
        if (pool.isEmpty()) return null;
        String picked = pool.get(Math.floorMod(choice.nextInt(), pool.size()));
        if (picked.equalsIgnoreCase(current == null ? "" : current.trim())) {
            picked = pool.get(Math.floorMod(pool.indexOf(picked) + 1, pool.size()));
        }
        return picked;
    }

    List<String> complete(CommandSender sender, String[] args) {
        return args.length == 2 && "help".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("help") : List.of();
    }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEvent event) {
        if (kind(event.getItem()) != null && (event.getClickedBlock() == null || !org.bukkit.Tag.ANVIL.isTagged(event.getClickedBlock().getType()))) event.setCancelled(true);
    }
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
