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
    /** Сколько тиков копим опыт перед одной строкой над хотбаром. */
    private static final long FEEDBACK_DELAY_TICKS = 10L;

    private final ProfessionsPlugin plugin;
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
    }

    /* ---------------------------------- опыт ---------------------------------- */

    /** Крафт в верстаке: таблица профессий решает, считается ли предмет. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !honest(player)) {
            return;
        }
        Profession active = plugin.service().active(player.getUniqueId());
        if (active == null || event.getRecipe() == null) {
            return;
        }
        ItemStack result = event.getRecipe().getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        int per = ProfessionCatalog.craftXp(active, result.getType());
        if (per > 0) {
            award(player, active, per * Math.clamp(result.getAmount(), 1, 64));
        }
    }

    /** Выплавка: опыт по таблице и второй блок у камнереза. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        Player player = event.getPlayer();
        if (!honest(player)) {
            return;
        }
        Profession active = plugin.service().active(player.getUniqueId());
        if (active == null) {
            return;
        }
        int per = ProfessionCatalog.smeltXp(active, event.getItemType());
        if (per <= 0) {
            return;
        }
        award(player, active, per * Math.clamp(event.getItemAmount(), 1, 64));

        if (active != Profession.MASON || !plugin.config().perkMasonBlock()) {
            return;
        }
        int chance = plugin.service().masonChance(player.getUniqueId());
        if (chance <= 0 || random.nextInt(100) >= chance) {
            return;
        }
        ItemStack extra = new ItemStack(event.getItemType(), 1);
        Bukkit.getScheduler().runTask(plugin, () -> give(player, extra));
    }

    /** Варка: опыт алхимику и шанс на лишнюю склянку в свободном слоте. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        long now = System.currentTimeMillis();
        Owner owner = brewers.remove(key(event.getBlock()));
        if (owner == null || now - owner.at() > BREW_OWNER_TTL) {
            return;
        }
        Player player = Bukkit.getPlayer(owner.player());
        if (!honest(player)) {
            return;
        }
        Profession active = plugin.service().active(owner.player());
        if (active != Profession.ALCHEMIST) {
            return;
        }
        award(player, active, ProfessionCatalog.brewXp(active));

        if (!plugin.config().perkAlchemistBottle()) {
            return;
        }
        int chance = plugin.service().alchemistChance(owner.player());
        if (chance <= 0 || random.nextInt(100) >= chance) {
            return;
        }
        duplicate(event.getResults());
    }

    /** Кто-то открыл стойку — запоминаем, чтобы отдать ему опыт за варку. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Block block = brewingBlock(event.getInventory().getHolder());
        if (block != null) {
            remember(block, player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Block block = brewingBlock(event.getInventory().getHolder());
        if (block != null) {
            remember(block, player.getUniqueId());
        }
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
        if (per > 0) {
            award(player, active, per);
        }
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
        int before = input == null ? 0 : input.getAmount();
        ItemStack bonus = result.clone();
        bonus.setAmount(1);
        Bukkit.getScheduler().runTask(plugin, () -> checkStonecut(player, active, per,
                inputType, before, bonus));
    }

    /** Проверяет, что распил состоялся, и начисляет опыт за каждый срез. */
    private void checkStonecut(Player player, Profession active, int per, Material inputType,
                               int before, ItemStack bonus) {
        if (!player.isOnline()) {
            return;
        }
        int consumed = 1;
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getType() == InventoryType.STONECUTTER) {
            ItemStack left = view.getTopInventory().getItem(STONECUTTER_INPUT);
            if (left == null || left.getType().isAir()) {
                consumed = Math.max(1, before);
            } else if (inputType == null || left.getType() == inputType) {
                consumed = Math.max(0, before - left.getAmount());
            }
            if (consumed <= 0) {
                return;
            }
        }
        if (plugin.service().active(player.getUniqueId()) != active) {
            return;
        }
        award(player, active, per * consumed);
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
        cappedNotice.remove(id);
        Feedback pending = feedback.remove(id);
        if (pending != null && pending.taskId >= 0) {
            Bukkit.getScheduler().cancelTask(pending.taskId);
        }
    }

    /* -------------------------------- помощники -------------------------------- */

    /** Начисляет опыт и сообщает о новом уровне; про потолок — один раз в день. */
    private void award(Player player, Profession profession, int xp) {
        if (xp <= 0) {
            return;
        }
        ProfessionService.Award award = plugin.service().award(player.getUniqueId(), profession,
                xp, System.currentTimeMillis());
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

    private static boolean honest(Player player) {
        return player != null && player.isOnline() && player.getGameMode() != GameMode.CREATIVE;
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
