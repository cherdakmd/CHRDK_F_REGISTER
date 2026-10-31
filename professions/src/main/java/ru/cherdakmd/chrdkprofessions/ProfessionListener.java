package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * События профессий (1.4.165): опыт за работу и тихие прибавки.
 *
 * <p>Опыт считается по событиям, которые нельзя подделать клиентом: крафт в
 * верстаке, выплавка из печи, завершённая варка, сломанный блок и распил на
 * камнерезе (1.4.177). Шифт-крафт даёт опыт за один результат рецепта — так же,
 * как это считают достижения и боевой пропуск: счётчик не раздувается, а честный
 * игрок не проигрывает от привычки. Креатив не считается.</p>
 *
 * <p>О каждом начислении игрок узнаёт строкой над хотбаром: опыт, накопленный за
 * полсекунды, складывается в одну строку, чтобы добыча камня не превращала
 * экран в мигающую ленту. Уровень по-прежнему объявляется в чате.</p>
 *
 * <p>Прибавки применяются только у выбранной профессии и только если они
 * включены в конфиге. Ни одна из них не выдаёт алмазы, незерит или монеты:
 * кузнец экономит уровни на наковальне, алхимик — склянку, повар — голод,
 * камнерез — блок.</p>
 */
final class ProfessionListener implements Listener {

    /** Сколько секунд помним, кто поставил ингредиент в варочную стойку. */
    private static final long BREW_OWNER_TTL = 5 * 60 * 1_000L;
    /** Потолок карты владельцев стоек: защита от утечки на больших фермах. */
    private static final int BREW_OWNER_LIMIT = 1_024;
    /** Слот входа и слот результата камнереза (1.4.177). */
    private static final int STONECUTTER_INPUT = 0;
    private static final int STONECUTTER_RESULT = 1;
    /** Слот результата наковальни: через него кузнец забирает работу (1.4.178). */
    private static final int ANVIL_RESULT = 2;
    /** Сколько тиков копим опыт перед одной строкой над хотбаром. */
    private static final long FEEDBACK_DELAY_TICKS = 10L;

    private final ProfessionsPlugin plugin;
    private final ProfessionOrigins origins;
    private final java.util.Set<UUID> benchPending=new java.util.HashSet<>();
    private final Map<BlockKey,UUID> stationPending=new HashMap<>();
    private final java.util.Map<UUID,UUID> casts=new HashMap<>();
    private final java.util.Set<UUID> crafting=new java.util.HashSet<>();
    private final Random random = new Random();
    /** Кто последним трогал варочную стойку: у BrewEvent нет игрока. */
    private final Map<BlockKey, Owner> brewers = new ConcurrentHashMap<>();
    /** Кому уже сказали про суточный потолок (дата, чтобы сказать один раз в день). */
    private final Map<UUID, String> cappedNotice = new ConcurrentHashMap<>();
    /** Защита от повторного входа в PrepareAnvilEvent, если сервер пересчитает цену. */
    private final AtomicBoolean adjustingAnvil = new AtomicBoolean();
    /** Накопленный опыт, ещё не показанный игроку. */
    private final Map<UUID, Feedback> feedback = new HashMap<>();

    private record BlockKey(UUID world, int x, int y, int z) {
    }

    private record Owner(UUID player, long at) {
    }

    /** Строка «+N опыта» собирается из нескольких начислений подряд. */
    private static final class Feedback {
        private Profession profession;
        private int amount;
        private int taskId = -1;
    }

    ProfessionListener(ProfessionsPlugin plugin) {
        this.plugin = plugin;
        this.origins=new ProfessionOrigins(plugin);
        Bukkit.getPluginManager().registerEvents(origins,plugin);
    }

    /* ---------------------------------- опыт ---------------------------------- */

    /** Число рецептов определяется расходом матрицы, включая shift-click. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onCraft(CraftItemEvent event){
        if(!(event.getWhoClicked() instanceof Player player)||!honest(player)||event.getRecipe()==null)return;
        Profession active=plugin.service().active(player.getUniqueId());
        if(active==null||!crafting.add(player.getUniqueId()))return;
        ItemStack result=event.getRecipe().getResult();int per=ProfessionCatalog.craftXp(active,result.getType());
        int outputBefore=outputCount(player,result.getType());
        ItemStack[] before=java.util.Arrays.stream(event.getInventory().getMatrix()).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);
        var inventory=event.getInventory();
        Bukkit.getScheduler().runTask(plugin,()->{
            crafting.remove(player.getUniqueId());if(!honest(player)||per<=0)return;
            for(ItemStack item:before)if(item!=null&&!ProfessionCatalog.craftInput(active,item.getType()))return;
            if(player.getOpenInventory().getTopInventory()!=inventory)return;
            ItemStack[] after=inventory.getMatrix();if(before.length!=after.length)return;
            int recipes=Integer.MAX_VALUE;
            for(int i=0;i<before.length;i++)if(before[i]!=null&&!before[i].getType().isAir()){
                ItemStack a=after[i];int remaining=a!=null&&a.isSimilar(before[i])?a.getAmount():0;
                int used=before[i].getAmount()-remaining;
                // Остатки вроде ведра не означают повторное использование входа.
                if(used<=0)return;recipes=Math.min(recipes,used);
            }
            if(recipes!=Integer.MAX_VALUE){
                int gained=Math.max(0,outputCount(player,result.getType())-outputBefore);
                int units=Math.min(gained,Math.min(4096,recipes*result.getAmount()));
                if(units>0)source(player,active,"craft",result.getType(),units,per);
            }
        });
    }
    /** Получение результата не присваивает чужой/автоматический труд. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onSmelt(org.bukkit.event.inventory.FurnaceSmeltEvent event){
        UUID owner=origins.take(event.getBlock(),event.getSource());if(owner==null)return;
        if(ProfessionCatalog.smeltXp(Profession.MASON,event.getResult().getType())>0)origins.output(event.getBlock(),owner,event.getResult().getType());
        Player player=Bukkit.getPlayer(owner);if(!honest(player))return;
        Profession active=plugin.service().active(owner);if(active==null)return;
        int per=ProfessionCatalog.smeltXp(active,event.getResult().getType());
        if(per<=0)return;
        source(player,active,"smelt",event.getResult().getType(),1,per);

    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event){
        Player player=event.getPlayer();
        boolean own=origins.extract(event.getBlock(),player.getUniqueId(),event.getItemType(),event.getItemAmount());
        if(own&&honest(player)&&plugin.service().active(player.getUniqueId())==Profession.MASON)extraFurnaceBlock(player,event.getItemType());
    }
    /** Прибавка камнереза: шанс на второй блок при плавке (печь, печь-домна, коптильня). */
    private void extraFurnaceBlock(Player player, Material material) {
        if (!plugin.config().perkMasonBlock()) {
            return;
        }
        int chance = plugin.service().masonChance(player.getUniqueId());
        if (chance <= 0 || random.nextInt(100) >= chance) {
            return;
        }
        ItemStack extra = new ItemStack(material, 1);
        Bukkit.getScheduler().runTask(plugin, () -> give(player, extra));
    }

    /** Опыт только тому, кто вручную внёс расходуемый реагент. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onBrew(BrewEvent event){
        UUID owner=origins.take(event.getBlock(),event.getContents().getIngredient());
        if(owner==null)return;Player player=Bukkit.getPlayer(owner);
        if(!honest(player)||plugin.service().active(owner)!=Profession.ALCHEMIST)return;
        int count=bottles(event.getResults());if(count<=0)return;
        source(player,Profession.ALCHEMIST,"brew",Material.POTION,1,ProfessionCatalog.brewXp(Profession.ALCHEMIST,count));
        if(plugin.config().perkAlchemistBottle()&&random.nextInt(100)<plugin.service().alchemistChance(owner))duplicate(event.getResults());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onClick(InventoryClickEvent event){
        if(event.getWhoClicked() instanceof Player player&&honest(player))watchStation(player,event.getInventory());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onDrag(InventoryDragEvent event){
        if(event.getWhoClicked() instanceof Player player&&honest(player))watchStation(player,event.getInventory());
    }
    private void watchStation(Player player,org.bukkit.inventory.Inventory inventory){
        ItemStack input=stationInput(inventory);if(!(inventory.getHolder() instanceof org.bukkit.block.TileState state))return;
        Block block=state.getBlock();Material type=block.getType();ItemStack before=input==null?null:input.clone();
        BlockKey key=key(block);UUID existing=stationPending.putIfAbsent(key,player.getUniqueId());
        if(existing!=null){if(!existing.equals(player.getUniqueId()))stationPending.put(key,new UUID(0,0));return;}
        Map<Material,Integer> stock=stationStock(player);
        Bukkit.getScheduler().runTask(plugin,()->{
            UUID actor=stationPending.remove(key);
            if(!player.getUniqueId().equals(actor)||!honest(player)||block.getType()!=type){origins.clear(block);return;}
            ItemStack after=stationInput(inventory);if(java.util.Objects.equals(before,after))return;
            int old=before!=null&&after!=null&&before.isSimilar(after)?before.getAmount():0;
            int incoming=after==null?0:after.getAmount()-old;
            if(incoming>0&&stock.getOrDefault(after.getType(),0)-stationStock(player).getOrDefault(after.getType(),0)<incoming){origins.clear(block);return;}
            origins.load(block,player.getUniqueId(),before,after);
        });
    }
    private static int outputCount(Player player,Material type){
        int amount=0;for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&item.getType()==type)amount+=item.getAmount();
        ItemStack cursor=player.getItemOnCursor();if(cursor!=null&&cursor.getType()==type)amount+=cursor.getAmount();
        ItemStack offhand=player.getInventory().getItemInOffHand();if(offhand.getType()==type)amount+=offhand.getAmount();return amount;
    }
    private static Map<Material,Integer> stationStock(Player player){
        Map<Material,Integer> amounts=new java.util.EnumMap<>(Material.class);
        for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&ProfessionWorkshop.ordinary(item))amounts.merge(item.getType(),item.getAmount(),Integer::sum);
        ItemStack cursor=player.getItemOnCursor();if(cursor!=null&&ProfessionWorkshop.ordinary(cursor))amounts.merge(cursor.getType(),cursor.getAmount(),Integer::sum);
        return amounts;
    }
    private static ItemStack stationInput(org.bukkit.inventory.Inventory inventory){
        if(inventory instanceof org.bukkit.inventory.FurnaceInventory furnace)return furnace.getSmelting();
        if(inventory instanceof org.bukkit.inventory.BrewerInventory brewer)return brewer.getIngredient();return null;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent event){
        Player player=event.getPlayer();if(!honest(player))return;
        if(event.getState()==org.bukkit.event.player.PlayerFishEvent.State.FISHING){casts.put(player.getUniqueId(),event.getHook().getUniqueId());return;}
        if(event.getState()!=org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH)return;
        UUID cast=casts.remove(player.getUniqueId());
        if(cast==null||!cast.equals(event.getHook().getUniqueId())||!event.getHook().isInOpenWater()
                ||!(event.getCaught() instanceof org.bukkit.entity.Item item))return;
        if(plugin.service().active(player.getUniqueId())==Profession.FISHER)
            source(player,Profession.FISHER,"fish",item.getItemStack().getType(),1,ProfessionCatalog.fishXp(item.getItemStack().getType()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onHarvest(org.bukkit.event.player.PlayerHarvestBlockEvent event){
        Player player=event.getPlayer();if(!honest(player))return;
        Profession active=plugin.service().active(player.getUniqueId());if(active==null)return;
        Block block=event.getHarvestedBlock();
        if(block.getType()!=Material.SWEET_BERRY_BUSH||!(block.getBlockData() instanceof org.bukkit.block.data.Ageable age)||age.getAge()<2)return;
        source(player,active,"harvest",block.getType(),1,ProfessionCatalog.miningXp(active,block.getType()));
    }

    /**
     * Сломанный блок: опыт за повседневную работу и уборка записи о стойке.
     *
     * <p>До 1.4.177 добыча не давала ничего, поэтому камнерез, который честно
     * ломал камень, не видел ни опыта, ни прогресса. Считается каждый блок из
     * таблицы ремесла: один блок — один шаг, без множителей за инструмент.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.BREWING_STAND) {
            brewers.remove(key(block));
        }
        Player player = event.getPlayer();
        if (!honest(player)) {
            return;
        }
        Profession active = plugin.service().active(player.getUniqueId());
        if (active == null) {
            return;
        }
        int per = ProfessionCatalog.miningXp(active, block.getType());
        boolean blocked=origins.placed(block)||(block.getType().name().endsWith("_ORE")&&player.getInventory().getItemInMainHand().containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH));origins.mark(block,false);origins.clear(block);
        if(per>0&&!blocked&&ProfessionOrigins.mature(block))source(player,active,"harvest",block.getType(),1,per);
    }

    /**
     * Камнерез: опыт за распил (1.4.177).
     *
     * <p>У камнереза нет своего события, как у верстака: результат считается на
     * месте, а в ванильном сервере он вообще не проходит через
     * {@code CraftItemEvent}. Поэтому опыт начисляется за клик по слоту
     * результата и проверяется на следующем тике — по тому, сколько ингредиента
     * действительно ушло. Так шифт-клик, срезающий полстака, даёт опыт за все
     * срезы, а отменённый клик — ни за один.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStonecut(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !honest(player)) {
            return;
        }
        if (event.getRawSlot() != STONECUTTER_RESULT
                || event.getView().getTopInventory().getType() != InventoryType.STONECUTTER) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) {
            return;
        }
        Profession active = plugin.service().active(player.getUniqueId());
        if (active == null) {
            return;
        }
        int per = ProfessionCatalog.cutXp(active, result.getType());
        if (per <= 0) {
            return;
        }
        ItemStack input = event.getView().getTopInventory().getItem(STONECUTTER_INPUT);
        Material inputType = input == null ? null : input.getType();
        if(input==null||!ProfessionCatalog.cutInput(input.getType()))return;
        int before = input.getAmount();
        ItemStack bonus = result.clone();
        bonus.setAmount(1);
        if(!benchPending.add(player.getUniqueId()))return;
        Bukkit.getScheduler().runTask(plugin, () -> {benchPending.remove(player.getUniqueId());checkStonecut(player, active, per,
                inputType, before, bonus);});
    }

    /** Проверяет, что распил состоялся, и начисляет опыт за каждый срез. */
    private void checkStonecut(Player player, Profession active, int per, Material inputType,
                               int before, ItemStack bonus) {
        if (!player.isOnline()) {
            return;
        }
        int consumed = 0;
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getType() == InventoryType.STONECUTTER) {
            ItemStack left = view.getTopInventory().getItem(STONECUTTER_INPUT);
            if (left == null || left.getType().isAir()) {
                consumed = Math.max(0, before);
            } else if (inputType == null || left.getType() == inputType) {
                consumed = Math.max(0, before - left.getAmount());
            }
            if (consumed <= 0) {
                return;
            }
        }
        if (!honest(player)||consumed<=0||plugin.service().active(player.getUniqueId()) != active) {
            return;
        }
        source(player, active,"cut",bonus.getType(),consumed,per);
        extraCutBlock(player, bonus);
    }

    /**
     * Прибавка камнереза на камнерезе (1.4.177).
     *
     * <p>Прежний «второй блок» работал только в печи, а камнерез режет — то есть
     * прибавка висела на редком действии. Теперь тот же шанс (1 % за уровень,
     * потолок 15 %) срабатывает и на распиле: один лишний блок на клик, не
     * больше. Шанс берётся у сервиса, поэтому чужое ремесло и выключенная
     * прибавка молчат.</p>
     */
    private void extraCutBlock(Player player, ItemStack bonus) {
        if (bonus == null || bonus.getType().isAir()) {
            return;
        }
        int chance = plugin.service().masonChance(player.getUniqueId());
        if (chance <= 0 || random.nextInt(100) >= chance) {
            return;
        }
        give(player, bonus);
    }

    /**
     * Наковальня: опыт за починку, объединение и переименование (1.4.178).
     *
     * <p>Наковальня — главный инструмент кузнеца, а опыта за неё не было вовсе:
     * игрок чинил инструменты, платил уровни и видел только скидку. Опыт
     * начисляется за клик по слоту результата и подтверждается на следующем
     * тике по списанным уровням — так отказ «не хватает опыта» и отменённый
     * клик ничего не дают.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !honest(player)) {
            return;
        }
        if (event.getRawSlot() != ANVIL_RESULT
                || event.getView().getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) {
            return;
        }
        Profession active = plugin.service().active(player.getUniqueId());
        if (active != Profession.SMITH) {
            return;
        }
        int cost = event.getView() instanceof AnvilView anvil
                ? Math.max(0, anvil.getRepairCost()) : 0;
        int levelBefore = player.getLevel();
        if(!benchPending.add(player.getUniqueId()))return;
        Bukkit.getScheduler().runTask(plugin, () -> {benchPending.remove(player.getUniqueId());checkAnvil(player, active, levelBefore, cost);});
    }

    /** Проверяет, что наковальня списала уровни, и начисляет опыт за работу. */
    private void checkAnvil(Player player, Profession active, int levelBefore, int cost) {
        if (!player.isOnline()) {
            return;
        }
        // Списание уровней — самый честный признак: отказ сервер не проводит.
        boolean paid = player.getLevel() < levelBefore;
        boolean taken = false;
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getType() == InventoryType.ANVIL) {
            ItemStack left = view.getTopInventory().getItem(ANVIL_RESULT);
            taken = left == null || left.getType().isAir();
        }
        if (!honest(player)||!paid) {
            return;
        }
        if (plugin.service().active(player.getUniqueId()) != active) {
            return;
        }
        source(player,active,"anvil",Material.ANVIL,1,ProfessionCatalog.anvilXp(cost));
    }

    /**
     * Стол кузнеца: незеритовый апгрейд и узоры — тоже работа кузнеца (1.4.178).
     *
     * <p>Событие приходит до выдачи результата, поэтому слушаем на
     * {@code MONITOR} и после отмены: отменённый апгрейд опыта не даёт.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithItem(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !honest(player)) {
            return;
        }
        Profession active = plugin.service().active(player.getUniqueId());
        if (active == null) {
            return;
        }
        int xp=ProfessionCatalog.smithingXp(active);var inventory=event.getInventory();
        ItemStack template=inventory.getItem(0);if(xp<=0||template==null)return;
        ItemStack before=template.clone();if(!benchPending.add(player.getUniqueId()))return;
        Bukkit.getScheduler().runTask(plugin,()->{
            benchPending.remove(player.getUniqueId());if(!honest(player)||player.getOpenInventory().getTopInventory()!=inventory)return;ItemStack after=inventory.getItem(0);
            int rest=after!=null&&after.isSimilar(before)?after.getAmount():0;
            int used=Math.max(0,before.getAmount()-rest);
            if(used>0)source(player,active,"forge",Material.SMITHING_TABLE,used,xp);
        });
    }

    /* --------------------------------- прибавки -------------------------------- */

    /**
     * Скидка кузнеца на работу в наковальне.
     *
     * <p>Стоимость уменьшается до применения: игрок платит меньше уровней, но
     * результат и все правила наковальни остаются ванильными. Повторный вход
     * в событие (если сервер пересчитает цену) пропускается, чтобы скидка не
     * наложилась дважды.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!plugin.config().perkSmithAnvil()) {
            return;
        }
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        int percent = plugin.service().smithDiscount(player.getUniqueId());
        if (percent <= 0 || !adjustingAnvil.compareAndSet(false, true)) {
            return;
        }
        try {
            AnvilView view = event.getView();
            int cost = view.getRepairCost();
            if (cost <= 0) {
                return;
            }
            int discounted = (int) Math.max(0L, Math.round(cost * (100 - percent) / 100.0));
            if (discounted < cost) {
                view.setRepairCost(discounted);
            }
        } finally {
            adjustingAnvil.set(false);
        }
    }

    /** Сытный стол повара: еда даёт чуть больше насыщения. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        int bonus = plugin.service().cookSaturation(player.getUniqueId());
        if (bonus <= 0) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir() || !item.getType().isEdible()
                || item.getItemMeta() instanceof PotionMeta) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            float current = player.getSaturation();
            float limit = Math.min(20.0f, player.getFoodLevel());
            float next = Math.min(limit, current + bonus);
            if (next > current) {
                player.setSaturation(next);
            }
        });
    }

    /* --------------------------------- подсказки ------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Ник нужен админским командам и оффлайн-поиску: помним актуальный.
        plugin.store().rename(player.getUniqueId(), player.getName());
        if (!plugin.config().enabled() || !plugin.config().joinHint()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || plugin.service().active(player.getUniqueId()) != null) {
                return;
            }
            plugin.messages().send(player, "join-hint");
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cappedNotice.remove(id);casts.remove(id);crafting.remove(id);benchPending.remove(id);
        Feedback pending = feedback.remove(id);
        if (pending != null && pending.taskId >= 0) {
            Bukkit.getScheduler().cancelTask(pending.taskId);
        }
    }

    /* -------------------------------- помощники -------------------------------- */

    /** Начисляет опыт и сообщает о новом уровне; про потолок — один раз в день. */
    private void source(Player player,Profession profession,String action,Material material,int units,int per){
        if(per<=0||units<=0)return;
        processAward(player,profession,plugin.service().awardSource(player.getUniqueId(),profession,action,material,units,per,System.currentTimeMillis()));
    }
    private void processAward(Player player,Profession profession,ProfessionService.Award award){
        if (award.added() <= 0) {
            if (award.capped()) {
                noticeCap(player, profession);
            }
            return;
        }
        plugin.requestSave();
        queueFeedback(player, profession, award.added());
        if (!award.leveled()) {
            return;
        }
        cappedNotice.remove(player.getUniqueId());
        plugin.messages().send(player, "level-up",
                "profession", ProfessionText.nameComponent(plugin.messages(), profession),
                "level", String.valueOf(award.levelAfter()),
                "rank", ProfessionText.rankComponent(plugin.messages(), award.levelAfter()));
        if (plugin.config().actionbar()) {
            player.sendActionBar(plugin.messages().get("level-actionbar",
                    "profession", ProfessionText.nameComponent(plugin.messages(), profession),
                    "level", String.valueOf(award.levelAfter())));
        }
        if (plugin.config().xpFeedback()) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        }
    }

    /**
     * Копит опыт для строки над хотбаром (1.4.177).
     *
     * <p>Без этой строки опыт «не происходит»: до первого уровня 120 опыта, а
     * это сотня блоков камня. Одно начисление — ещё не повод для строки, но за
     * полсекунды работы их набегает несколько, и игрок видит «+5 опыта ·
     * 12/120» вместо тишины.</p>
     */
    private void queueFeedback(Player player, Profession profession, int amount) {
        if (!plugin.config().xpFeedback() || amount <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        Feedback pending = feedback.get(id);
        if (pending == null) {
            pending = new Feedback();
            pending.taskId = Bukkit.getScheduler()
                    .runTaskLater(plugin, () -> flushFeedback(id), FEEDBACK_DELAY_TICKS)
                    .getTaskId();
            feedback.put(id, pending);
        }
        pending.profession = profession;
        pending.amount += amount;
    }

    /** Показывает накопленный опыт одной строкой; молчит, если ремесло сменили. */
    private void flushFeedback(UUID id) {
        Feedback pending = feedback.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (pending == null || pending.profession == null || player == null
                || !player.isOnline()) {
            return;
        }
        if (plugin.service().active(id) != pending.profession) {
            return;
        }
        int maxLevel = plugin.service().maxLevel(id);
        int xp = plugin.service().xp(id, pending.profession);
        int level = plugin.service().level(id, pending.profession);
        int needed = ProfessionMath.needed(level, maxLevel);
        player.sendActionBar(plugin.messages().get("xp-gain",
                "profession", ProfessionText.nameComponent(plugin.messages(), pending.profession),
                "amount", String.valueOf(pending.amount),
                "xp", String.valueOf(ProfessionMath.progress(xp, maxLevel)),
                "needed", needed <= 0 ? "∞" : String.valueOf(needed),
                "percent", String.valueOf(ProfessionMath.progressPercent(xp, maxLevel))));
    }

    private void noticeCap(Player player, Profession profession) {
        String today = ProfessionService.utcDay(System.currentTimeMillis());
        if (today.equals(cappedNotice.get(player.getUniqueId()))) {
            return;
        }
        cappedNotice.put(player.getUniqueId(), today);
        plugin.messages().send(player, "daily-cap",
                "profession", ProfessionText.nameComponent(plugin.messages(), profession));
    }

    /** Сколько склянок реально вышло из стойки: полная стойка выгоднее (1.4.178). */
    private static int bottles(List<ItemStack> results) {
        if (results == null) {
            return 1;
        }
        int count = 0;
        for (ItemStack stack : results) {
            if (stack != null && !stack.getType().isAir()) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    /** Копия первого зелья в свободный слот стойки; {@code false}, если места нет. */
    private static boolean duplicate(List<ItemStack> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        ItemStack source = null;
        for (ItemStack stack : results) {
            if (stack != null && !stack.getType().isAir()) {
                source = stack;
                break;
            }
        }
        if (source == null) {
            return false;
        }
        ItemStack copy = source.clone();
        copy.setAmount(1);
        try {
            for (int index = 0; index < results.size(); index++) {
                ItemStack slot = results.get(index);
                if (slot == null || slot.getType().isAir()) {
                    results.set(index, copy);
                    return true;
                }
            }
            if (results.size() < 3) {
                results.add(copy);
                return true;
            }
        } catch (UnsupportedOperationException immutable) {
            return false;
        }
        return false;
    }

    /** Выдаёт предмет в инвентарь; если места нет — роняет под ноги. */
    private static void give(Player player, ItemStack stack) {
        if (player == null || !player.isOnline() || stack == null) {
            return;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        if (left.isEmpty()) {
            return;
        }
        Location location = player.getLocation();
        for (ItemStack drop : left.values()) {
            player.getWorld().dropItemNaturally(location, drop);
        }
    }

    private boolean honest(Player player) {
        return plugin.config().enabled()&&player!=null&&player.isOnline()&&player.getGameMode()==GameMode.SURVIVAL
                &&player.hasPermission("chrdkprofessions.use")&&plugin.authenticated(player);
    }

    private static Block brewingBlock(InventoryHolder holder) {
        return holder instanceof BrewingStand stand ? stand.getBlock() : null;
    }

    private void remember(Block block, UUID player) {
        long now = System.currentTimeMillis();
        if (brewers.size() > BREW_OWNER_LIMIT) {
            brewers.entrySet().removeIf(entry -> now - entry.getValue().at() > BREW_OWNER_TTL);
        }
        brewers.put(key(block), new Owner(player, now));
    }

    private static BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
