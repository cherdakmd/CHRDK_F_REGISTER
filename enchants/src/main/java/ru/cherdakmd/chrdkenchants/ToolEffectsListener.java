package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Native break для каждого блока; бонусы только после финального разрешённого дропа. */
public final class ToolEffectsListener implements Listener {
    private record Key(UUID world, int x, int y, int z, UUID player) { }
    private record Source(BlockBreakEvent event, Material type, ItemStack tool, long generation,
                          boolean secondary, boolean sneaking, boolean natural, int slot, org.bukkit.util.Vector direction) { }
    private record Yield(Item entity, int amount, Material material) { }
    private final EnchantsPlugin plugin;
    private final MiningOrigin origin;
    private final MiningTasks tasks;
    private final Map<Key, Source> sources = new HashMap<>();
    private final Set<UUID> secondary = new HashSet<>();
    ToolEffectsListener(EnchantsPlugin plugin, MiningOrigin origin) {
        this.plugin = plugin; this.origin = origin;
        tasks = new MiningTasks(action -> { var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel; });
    }
    void clear() { tasks.clear(); sources.clear(); secondary.clear(); }
    private void forget(Player player) {
        tasks.cancel(player.getUniqueId());
        sources.keySet().removeIf(key -> key.player.equals(player.getUniqueId()));
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) { forget(event.getPlayer()); }
    @EventHandler public void death(org.bukkit.event.entity.PlayerDeathEvent event) { forget(event.getEntity()); }
    @EventHandler public void world(org.bukkit.event.player.PlayerChangedWorldEvent event) { forget(event.getPlayer()); }
    private Key key(Block block, Player player) {
        return new Key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), player.getUniqueId());
    }
    private boolean eligible(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && player.hasPermission("chrdkenchants.use") && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }
    private int level(ItemStack tool, String id) {
        EnchantDef def = EnchantRegistry.byId(id);
        if (def == null || !plugin.config().isEnabled(id) || !def.supports(tool.getType().name())) return 0;
        return Math.clamp(plugin.items().read(tool).getOrDefault(id, 0), 0, def.maxLevel());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer(); ItemStack tool = player.getInventory().getItemInMainHand();
        if (!eligible(player) || tool.getAmount() != 1 || sources.size() >= 4096) return;
        if (level(tool, "midas") + level(tool, "fortunate") + level(tool, "autosmelt")
                + level(tool, "veinminer") + level(tool, "excavator") == 0) return;
        Block block = event.getBlock(); Key key = key(block, player);
        Source source = new Source(event, block.getType(), tool.clone(), plugin.sourceGeneration(),
                secondary.contains(player.getUniqueId()), player.isSneaking(), MiningPolicy.ore(block.getType().name()) && !origin.denied(block), player.getInventory().getHeldItemSlot(), player.getLocation().getDirection().clone());
        sources.put(key, source);
        if (!tasks.submit(player.getUniqueId(), source, () -> {
            sources.remove(key, source);
            if (source.secondary || !valid(source) || !source.sneaking || (!block.getType().isAir() && !block.isLiquid())
                    || player.getInventory().getHeldItemSlot() != source.slot
                    || !PveEquipment.same(source.tool, player.getInventory().getItemInMainHand())) return;
            int vein = level(source.tool, "veinminer"), area = level(source.tool, "excavator");
            if (vein > 0 && MiningPolicy.ore(source.type.name())) vein(player, block, source, EnchantParams.value("veinminer", vein));
            else if (area > 0) area(player, block, source, area);
        })) sources.remove(key, source);
    }
    private boolean valid(Source source) {
        Player player = source.event.getPlayer();
        return !source.event.isCancelled() && source.event.isDropItems() && source.generation == plugin.sourceGeneration()
                && eligible(player) && player.getWorld().equals(source.event.getBlock().getWorld())
                && player.getWorld().isChunkLoaded(source.event.getBlock().getX() >> 4, source.event.getBlock().getZ() >> 4);
    }
    /** Меняем только настоящие объекты ванильного drop-события. При его отмене игра их не выдаст. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drops(BlockDropItemEvent event) {
        Source source = sources.get(key(event.getBlock(), event.getPlayer()));
        if (source == null || !valid(source) || event.getBlockState().getType() != source.type || event.getItems().size() > 64) return;
        boolean silk = source.tool.containsEnchantment(Enchantment.SILK_TOUCH);
        boolean smelt = level(source.tool, "autosmelt") > 0;
        List<Yield> yields = new ArrayList<>();
        for (Item entity : List.copyOf(event.getItems())) {
            ItemStack stack = entity.getItemStack();
            if (stack.hasItemMeta() || stack.getAmount() <= 0 || stack.getAmount() > stack.getMaxStackSize()) continue;
            Material before = stack.getType();
            if (smelt) {
                Material after = Material.valueOf(MiningPolicy.smelt(source.type.name(), before.name(), silk));
                if (after != before) { ItemStack converted = stack.clone(); converted.setType(after); entity.setItemStack(converted); }
            }
            if (source.natural && !source.secondary && !silk && before.name().equals(MiningPolicy.mineral(source.type.name())))
                yields.add(new Yield(entity, stack.getAmount(), before));
        }
        if (yields.isEmpty() || level(source.tool, "midas") + level(source.tool, "fortunate") == 0) return;
        tasks.submit(event.getPlayer().getUniqueId(), yields, () -> bonus(event, source, yields));
    }
    private void bonus(BlockDropItemEvent event, Source source, List<Yield> yields) {
        if (event.isCancelled() || !valid(source) || origin.denied(event.getBlock())) return;
        List<Yield> available = yields.stream().filter(yield -> event.getItems().contains(yield.entity)
                && yield.entity.isValid() && !yield.entity.isDead() && yield.entity.getWorld().equals(event.getBlock().getWorld())
                && yield.entity.getItemStack().getAmount() >= yield.amount
                && (yield.entity.getItemStack().getType() == yield.material
                || yield.entity.getItemStack().getType().name().equals(MiningPolicy.smelt(source.type.name(), yield.material.name(), false))))
                .toList();
        if (available.isEmpty()) return;
        int midas = level(source.tool, "midas"), fortunate = level(source.tool, "fortunate");
        boolean success = midas > 0 && roll(EnchantParams.chancePercent("midas", midas))
                || fortunate > 0 && roll(EnchantParams.percent("fortunate", fortunate));
        if (!success) return;
        int remaining = 64; boolean dropped = false;
        for (Yield yield : available) {
            int amount = Math.min(remaining, yield.amount); if (amount <= 0) break; remaining -= amount;
            // Не копируем PDC/имя чужой награды: только новый обычный минерал, в базовом количестве.
            Item item = event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(.5,.5,.5),
                    new ItemStack(yield.entity.getItemStack().getType(), amount));
            dropped |= item.isValid() && !item.isDead();
        }
        if (dropped) event.getPlayer().playSound(event.getBlock().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .5f, 1.6f);
    }
    private boolean roll(int percent) { return percent > 0 && plugin.service().random().nextInt(100) < percent; }
    private Block target(Block center, int x, int y, int z) {
        int px = center.getX() + x, py = center.getY() + y, pz = center.getZ() + z;
        if (py < center.getWorld().getMinHeight() || py >= center.getWorld().getMaxHeight()
                || !center.getWorld().isChunkLoaded(px >> 4, pz >> 4)) return null;
        return center.getWorld().getBlockAt(px, py, pz);
    }
    private boolean breakOne(Player player, Block block, Source source) {
        if (block == null || !valid(source) || player.getInventory().getHeldItemSlot() != source.slot || !PveEquipment.same(source.tool, player.getInventory().getItemInMainHand())
                || !(player.getEyeLocation().distanceSquared(block.getLocation().add(.5,.5,.5)) <= 25)
                || block.isLiquid() || block.getType().isAir() || block.getType().getHardness() <= 0
                || block.getType().getHardness() >= 30 || block.getState() instanceof org.bukkit.block.TileState) return false;
        secondary.add(player.getUniqueId());
        try { return player.breakBlock(block); }
        finally { secondary.remove(player.getUniqueId()); }
    }
    private void vein(Player player, Block start, Source source, int maximum) {
        Deque<Block> queue = new ArrayDeque<>(); Set<String> seen = new HashSet<>();
        queue.add(start); seen.add(start.getX()+":"+start.getY()+":"+start.getZ()); int count = 1;
        while (!queue.isEmpty() && count < maximum) {
            Block current = queue.removeFirst();
            for (var offset : MiningPolicy.NEIGHBORS) {
                if (count >= maximum) break;
                Block block = target(current, offset.x(), offset.y(), offset.z()); if (block == null) continue;
                if (!seen.add(block.getX()+":"+block.getY()+":"+block.getZ()) || block.getType() != source.type) continue;
                if (breakOne(player, block, source)) { count++; queue.add(block); }
            }
        }
    }
    private void area(Player player, Block center, Source source, int level) {
        var direction = source.direction;
        for (var offset : MiningPolicy.area(direction.getX(), direction.getY(), direction.getZ(), level))
            breakOne(player, target(center, offset.x(), offset.y(), offset.z()), source);
    }
}
