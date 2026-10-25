package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Охота: добыча с мобов, обмен монет, лавка, сундуки и трофеи (1.4.155).
 *
 * <p>Монеты и осколки начисляются прямо в кошелёк — это не предметы, поэтому их
 * нельзя потерять при смерти и нельзя продублировать. Ванильный дроп падает
 * обычными предметами: сначала в инвентарь, остаток — под ноги.</p>
 *
 * <p>Антифарм опирается на причину спавна сущности
 * ({@link LivingEntity#getEntitySpawnReason()}): мобы со спавнера, из яйца или
 * созданные командой не дают добычи. Это отсекает классические фермы, не
 * запрещая честную охоту.</p>
 */
public final class HuntService {

    /** Сколько алмазов класть в один стак при выдаче. */
    private static final int DIAMOND_STACK = 64;
    /** Потолок покупок за один раз — защита от случайного «купи 1000». */
    private static final int MAX_PURCHASE = 64;

    private final HuntPlugin plugin;
    private final Random random = new Random();
    /** День, в который игроку уже сказали про суточный потолок монет. */
    private final Map<UUID, Long> capWarned = new ConcurrentHashMap<>();

    public HuntService(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    /** Забывает разовую отметку «сказали про потолок» при выходе игрока. */
    void forget(UUID id) {
        if (id != null) {
            capWarned.remove(id);
        }
    }

    /* --------------------------------- охота ---------------------------------- */

    /**
     * Начисляет добычу за убийство моба.
     *
     * @return {@code true}, если моб вообще входит в охоту
     */
    public boolean handleDeath(Player killer, LivingEntity dead) {
        if (killer == null || dead == null) {
            return false;
        }
        HuntConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return false;
        }
        if (killer.getWorld() != null && config.worldDisabled(killer.getWorld().getName())) {
            return false;
        }
        MobRule rule = config.ruleFor(dead.getType());
        if (rule == null || !rule.usable()) {
            return false;
        }
        if (blockedBySpawn(dead)) {
            return false;
        }
        HuntFamily family = config.family(rule.familyId());
        if (family == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        UUID id = killer.getUniqueId();
        KillResult rolled = HuntRoll.kill(random, rule, family);
        long allowed = coinsLeft(id, now, config);
        long coins = Math.min(rolled.coins(), allowed);

        plugin.store().touchDay(id, killer.getName(), now);
        if (coins > 0L) {
            plugin.store().addCoins(id, killer.getName(), coins);
        }
        if (rolled.shards() > 0) {
            plugin.store().addShards(id, killer.getName(), family.id(), rolled.shards());
        }
        if (rolled.keys() > 0) {
            plugin.store().addKeys(id, killer.getName(), family.id(), rolled.keys());
        }
        plugin.store().registerKill(id, killer.getName(), family.id(), now);
        plugin.requestSave();

        int dropped = 0;
        int rankBonus = 0;
        for (HuntDrop drop : rolled.drops()) {
            int extra = rankExtraDrops(killer, drop.amount());
            rankBonus += extra;
            dropped += giveOrDrop(killer, new ItemStack(drop.material(), drop.amount() + extra));
        }
        if (rankBonus > 0) {
            plugin.messages().send(killer, "rank-extra-loot", "amount", String.valueOf(rankBonus));
        }
        if (dropped > 0 && rule.boss()) {
            plugin.messages().send(killer, "kill-dropped", "amount", String.valueOf(dropped));
        }
        if (coins < rolled.coins()) {
            warnCap(killer, now, config);
        }
        feedback(killer, family, coins, rolled.shards(), rolled.keys(), rule.boss(), config.actionbar());
        recordBestiary(killer, dead.getType());
        return true;
    }

    /**
     * Прибавка добычи от донатного ранга (1.4.167).
     *
     * <p>Бонус считается от количества предметов и округляется вниз, поэтому
     * одна находка не превращается в две: на вершине лестницы (+25 %) игрок
     * получает лишний предмет с каждой четвёртой. Алмазы, монеты и осколки
     * бонус не трогают — это валюта, а не добыча; потолок зафиксирован в
     * {@code RankBonus.MAX_DROP_PERCENT}.</p>
     */
    private int rankExtraDrops(Player player, int amount) {
        if (player == null || amount <= 0) {
            return 0;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            return 0;
        }
        return api.rankBonus(player.getName()).extraDrops(amount);
    }

    /** Отсекает спавнеры, яйца и команды — классические фермы. */
    private boolean blockedBySpawn(LivingEntity dead) {
        HuntConfig config = plugin.config();
        if (config.blockSpawners() && dead.fromMobSpawner()) {
            return true;
        }
        CreatureSpawnEvent.SpawnReason reason;
        try {
            reason = dead.getEntitySpawnReason();
        } catch (RuntimeException exception) {
            return false;
        }
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case SPAWNER, TRIAL_SPAWNER -> config.blockSpawners();
            case SPAWNER_EGG, EGG -> config.blockEggs();
            case COMMAND, BUILD_WITHER -> config.blockCommand();
            default -> false;
        };
    }

    /** Сколько монет ещё можно заработать сегодня; {@link Long#MAX_VALUE} — без лимита. */
    public long coinsLeft(UUID id, long now, HuntConfig config) {
        if (config.coinsPerDay() <= 0) {
            return Long.MAX_VALUE;
        }
        long today = plugin.store().coinsToday(id, now);
        return Math.max(0L, config.coinsPerDay() - today);
    }

    private void warnCap(Player player, long now, HuntConfig config) {
        long day = HuntStore.dayOf(now);
        Long warned = capWarned.get(player.getUniqueId());
        if (warned != null && warned == day) {
            return;
        }
        capWarned.put(player.getUniqueId(), day);
        plugin.messages().send(player, "coins-cap", "limit", String.valueOf(config.coinsPerDay()));
    }

    /** Экшен-бар с добычей: показывает только то, что реально получено. */
    private void feedback(Player player, HuntFamily family, long coins, int shards, int keys,
                          boolean boss, boolean actionbar) {
        if (actionbar && (coins > 0L || shards > 0 || keys > 0)) {
            StringBuilder text = new StringBuilder();
            if (coins > 0L) {
                text.append(plugin.messages().raw("kill-coins", "coins", String.valueOf(coins)));
            }
            if (shards > 0) {
                if (!text.isEmpty()) {
                    text.append(plugin.messages().raw("kill-separator"));
                }
                text.append(plugin.messages().raw("kill-shards",
                        "shards", String.valueOf(shards), "shard", family.shardName()));
            }
            if (keys > 0) {
                if (!text.isEmpty()) {
                    text.append(plugin.messages().raw("kill-separator"));
                }
                text.append(plugin.messages().raw("kill-key",
                        "keys", String.valueOf(keys), "key", family.keyName()));
            }
            player.sendActionBar(plugin.messages().renderTemplate(text.toString()));
        }
        if (coins > 0L) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        }
        if (shards > 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.6f);
        }
        if (keys > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.3f);
        }
        if (boss) {
            String shard = family.shardName();
            plugin.messages().send(player, "boss-killed",
                    "family", family.displayName(),
                    "coins", String.valueOf(coins),
                    "shard", shard);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(player)) {
                    plugin.messages().send(online, "boss-broadcast",
                            "player", player.getName(),
                            "family", family.displayName());
                }
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
        }
    }

    /* -------------------------------- бестиарий -------------------------------- */

    /**
     * Записывает убийство в бестиарий и объявляет новый вид.
     *
     * <p>Повторные убийства только увеличивают счётчик: сообщение на каждый
     * удар превратило бы журнал в спам. Награда за раздел выдаётся сразу, как
     * закрыт последний вид, — без похода в меню.</p>
     */
    private void recordBestiary(Player killer, EntityType type) {
        HuntBestiary bestiary = plugin.config().bestiary();
        if (bestiary == null || bestiary.empty() || killer == null) {
            return;
        }
        HuntBestiary.Entry entry = bestiary.entryFor(type);
        if (entry == null) {
            return;
        }
        UUID id = killer.getUniqueId();
        long kills = plugin.store().recordBestiaryKill(id, killer.getName(), entry.key());
        if (kills != 1L) {
            return;
        }
        HuntBestiary.Section section = bestiary.section(entry.familyId());
        HuntBestiary.Progress progress = bestiary.progress(section, plugin.store().bestiaryMap(id));
        plugin.messages().send(killer, "bestiary-new",
                "mob", Messages.trusted(entry.translationTag()),
                "section", section == null ? "" : section.displayName(),
                "seen", String.valueOf(progress.seen()),
                "total", String.valueOf(progress.total()));
        plugin.requestSave();
        if (progress.complete()) {
            // Награду мог забрать и раньше — тогда молчим, а не пугаем игрока.
            grantBestiary(killer, section, true);
        }
    }

    /** Убийства вида по журналу ({@code 0}, если вид не открыт). */
    public long bestiaryKills(Player player, String key) {
        return player == null ? 0L : plugin.store().bestiaryKills(player.getUniqueId(), key);
    }

    /** Прогресс раздела для игрока. */
    public HuntBestiary.Progress bestiaryProgress(Player player, HuntBestiary.Section section) {
        HuntBestiary bestiary = plugin.config().bestiary();
        if (bestiary == null || player == null) {
            return new HuntBestiary.Progress(section == null ? "" : section.id(), 0,
                    section == null ? 0 : section.size(), false);
        }
        return bestiary.progress(section, plugin.store().bestiaryMap(player.getUniqueId()));
    }

    /** Прогресс всего журнала: сколько видов открыто. */
    public int bestiarySeen(Player player) {
        HuntBestiary bestiary = plugin.config().bestiary();
        if (bestiary == null || player == null) {
            return 0;
        }
        return bestiary.seenTotal(plugin.store().bestiaryMap(player.getUniqueId()));
    }

    /** Награда за раздел уже выдана. */
    public boolean bestiaryRewarded(Player player, String sectionId) {
        return player != null && plugin.store().hasBestiaryAward(player.getUniqueId(), sectionId);
    }

    /**
     * Выдаёт награду за закрытый раздел по запросу игрока (кнопка в меню).
     *
     * <p>Повторный клик ничего не дублирует: факт выдачи хранится в данных.</p>
     */
    public boolean claimBestiary(Player player, String sectionId) {
        if (player == null) {
            return false;
        }
        HuntBestiary bestiary = plugin.config().bestiary();
        HuntBestiary.Section section = bestiary == null ? null : bestiary.section(sectionId);
        if (section == null) {
            plugin.messages().send(player, "bestiary-unknown-section",
                    "id", sectionId == null ? "" : sectionId);
            return false;
        }
        return grantBestiary(player, section, false);
    }

    /**
     * Выдаёт награду раздела, если он закрыт и награда ещё не выдана.
     *
     * @param silentIfClaimed не сообщать «награда уже получена» (автовыдача)
     */
    private boolean grantBestiary(Player player, HuntBestiary.Section section,
                                  boolean silentIfClaimed) {
        HuntBestiary bestiary = plugin.config().bestiary();
        if (player == null || section == null || bestiary == null) {
            return false;
        }
        UUID id = player.getUniqueId();
        HuntBestiary.Progress progress = bestiary.progress(section, plugin.store().bestiaryMap(id));
        if (!progress.complete()) {
            plugin.messages().send(player, "bestiary-section-locked",
                    "section", section.displayName(),
                    "seen", String.valueOf(progress.seen()),
                    "total", String.valueOf(progress.total()));
            return false;
        }
        if (!plugin.store().grantBestiaryAward(id, player.getName(), section.id())) {
            if (!silentIfClaimed) {
                plugin.messages().send(player, "bestiary-reward-claimed",
                        "section", section.displayName());
            }
            return false;
        }
        HuntBestiary.Reward reward = section.reward();
        if (reward.coins() > 0) {
            plugin.store().addBonusCoins(id, player.getName(), reward.coins());
        }
        if (reward.shards() > 0) {
            plugin.store().addShards(id, player.getName(), section.id(), reward.shards());
        }
        if (reward.keys() > 0) {
            plugin.store().addKeys(id, player.getName(), section.id(), reward.keys());
        }
        plugin.messages().send(player, "bestiary-section-done",
                "section", section.displayName(),
                "total", String.valueOf(progress.total()),
                "reward", Messages.trusted(rewardText(section)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        plugin.requestSave();
        announceFull(player, bestiary);
        return true;
    }

    /** Разовая награда за весь журнал — после последнего закрытого раздела. */
    private void announceFull(Player player, HuntBestiary bestiary) {
        UUID id = player.getUniqueId();
        if (!bestiary.fullyComplete(plugin.store().bestiaryMap(id))
                || !plugin.store().grantBestiaryAward(id, player.getName(),
                HuntBestiary.FULL_AWARD)) {
            return;
        }
        if (bestiary.fullCoins() > 0) {
            plugin.store().addBonusCoins(id, player.getName(), bestiary.fullCoins());
        }
        plugin.messages().send(player, "bestiary-full",
                "coins", String.valueOf(bestiary.fullCoins()),
                "entries", String.valueOf(bestiary.totalEntries()));
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.equals(player)) {
                plugin.messages().send(online, "bestiary-full-broadcast", "player", player.getName());
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        plugin.requestSave();
    }

    /** Текст награды одной строкой: монеты, осколки и ключи семейства. */
    public String rewardText(HuntBestiary.Section section) {
        if (section == null) {
            return plugin.messages().raw("bestiary-reward-none");
        }
        HuntBestiary.Reward reward = section.reward();
        HuntFamily family = plugin.config().family(section.id());
        String shard = family == null ? "" : family.shardName();
        String key = family == null ? "" : family.keyName();
        StringBuilder text = new StringBuilder();
        if (reward.coins() > 0) {
            text.append(plugin.messages().raw("bestiary-reward-coins",
                    "coins", String.valueOf(reward.coins())));
        }
        if (reward.shards() > 0) {
            if (!text.isEmpty()) {
                text.append(plugin.messages().raw("kill-separator"));
            }
            text.append(plugin.messages().raw("bestiary-reward-shards",
                    "shards", String.valueOf(reward.shards()), "shard", shard));
        }
        if (reward.keys() > 0) {
            if (!text.isEmpty()) {
                text.append(plugin.messages().raw("kill-separator"));
            }
            text.append(plugin.messages().raw("bestiary-reward-keys",
                    "keys", String.valueOf(reward.keys()), "key", key));
        }
        return text.isEmpty() ? plugin.messages().raw("bestiary-reward-none") : text.toString();
    }

    /* --------------------------------- обмен ---------------------------------- */

    /** Обменивает монеты на алмазы в пределах суточного потолка. */
    public boolean exchange(Player player, int diamonds) {
        if (player == null) {
            return false;
        }
        HuntConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        int limit = config.exchangePerDay();
        if (limit <= 0) {
            plugin.messages().send(player, "exchange-closed");
            return false;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long left = Math.max(0L, limit - plugin.store().exchangeToday(id, now));
        if (left <= 0L) {
            plugin.messages().send(player, "exchange-limit", "limit", String.valueOf(limit));
            return false;
        }
        int min = Math.min(config.exchangeMin(), limit);
        int requested = Math.max(0, diamonds);
        if (requested < min) {
            plugin.messages().send(player, "exchange-min", "min", String.valueOf(min));
            return false;
        }
        if (requested > left) {
            plugin.messages().send(player, "exchange-limit", "limit", String.valueOf(left));
            return false;
        }
        long cost = (long) requested * config.coinsPerDiamond();
        long wallet = plugin.store().coins(id);
        if (wallet < cost) {
            plugin.messages().send(player, "exchange-no-coins",
                    "cost", String.valueOf(cost),
                    "coins", String.valueOf(wallet));
            return false;
        }
        if (!plugin.store().spendCoins(id, cost)) {
            plugin.messages().send(player, "exchange-no-coins",
                    "cost", String.valueOf(cost),
                    "coins", String.valueOf(wallet));
            return false;
        }
        plugin.store().recordExchange(id, player.getName(), now, requested);
        int dropped = payDiamonds(player, requested);
        plugin.requestSave();
        plugin.messages().send(player, "exchange-ok",
                "diamonds", String.valueOf(requested),
                "coins", String.valueOf(cost),
                "left", String.valueOf(left - requested));
        if (dropped > 0) {
            plugin.messages().send(player, "exchange-dropped");
        }
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.6f, 1.2f);
        return true;
    }

    /** Обменивает максимум, который позволяет потолок и кошелёк. */
    public boolean exchangeMax(Player player) {
        if (player == null) {
            return false;
        }
        HuntConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        int limit = config.exchangePerDay();
        if (limit <= 0) {
            plugin.messages().send(player, "exchange-closed");
            return false;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long left = Math.max(0L, limit - plugin.store().exchangeToday(id, now));
        if (left <= 0L) {
            plugin.messages().send(player, "exchange-limit", "limit", String.valueOf(limit));
            return false;
        }
        long affordable = plugin.store().coins(id) / config.coinsPerDiamond();
        int diamonds = (int) Math.min(left, affordable);
        if (diamonds <= 0) {
            plugin.messages().send(player, "exchange-no-coins",
                    "cost", String.valueOf(config.coinsPerDiamond()),
                    "coins", String.valueOf(plugin.store().coins(id)));
            return false;
        }
        return exchange(player, diamonds);
    }

    /** Сколько алмазов ещё можно обменять сегодня. */
    public long exchangeLeft(Player player) {
        HuntConfig config = plugin.config();
        if (player == null || config == null) {
            return 0L;
        }
        return Math.max(0L, config.exchangePerDay()
                - plugin.store().exchangeToday(player.getUniqueId(), System.currentTimeMillis()));
    }

    /** Выдаёт алмазы; возвращает количество, которое не влезло и упало под ноги. */
    private int payDiamonds(Player player, int total) {
        int remaining = Math.max(0, total);
        int dropped = 0;
        while (remaining > 0) {
            int chunk = Math.min(DIAMOND_STACK, remaining);
            remaining -= chunk;
            dropped += giveOrDrop(player, new ItemStack(Material.DIAMOND, chunk));
        }
        return dropped;
    }

    /* --------------------------------- лавка ---------------------------------- */

    /** Покупает позицию лавки; {@code quantity} — сколько покупок подряд. */
    public boolean buy(Player player, String offerId, int quantity) {
        if (player == null) {
            return false;
        }
        HuntConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        HuntShopOffer offer = config.offer(offerId);
        if (offer == null) {
            plugin.messages().send(player, "shop-unknown", "id", offerId == null ? "" : offerId);
            return false;
        }
        int count = Math.clamp(quantity, 1, MAX_PURCHASE);
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long earned = plugin.store().earned(id);
        if (earned < offer.minCoinsEarned()) {
            plugin.messages().send(player, "shop-locked",
                    "need", String.valueOf(offer.minCoinsEarned()),
                    "earned", String.valueOf(earned));
            return false;
        }
        int bought = plugin.store().purchasedToday(id, offer.id(), now);
        if (offer.dailyLimit() > 0 && bought + count > offer.dailyLimit()) {
            plugin.messages().send(player, "shop-limit",
                    "limit", String.valueOf(offer.dailyLimit()),
                    "bought", String.valueOf(bought));
            return false;
        }
        long cost = offer.totalPrice(count);
        long wallet = plugin.store().coins(id);
        if (wallet < cost) {
            plugin.messages().send(player, "shop-no-coins",
                    "price", String.valueOf(cost),
                    "coins", String.valueOf(wallet));
            return false;
        }
        if (!plugin.store().spendCoins(id, cost)) {
            plugin.messages().send(player, "shop-no-coins",
                    "price", String.valueOf(cost),
                    "coins", String.valueOf(wallet));
            return false;
        }
        plugin.store().recordPurchase(id, player.getName(), offer.id(), now, count);
        int total = offer.totalItems(count);
        int dropped = giveItems(player, offer.material(), total);
        plugin.requestSave();
        plugin.messages().send(player, "shop-bought",
                "item", itemName(offer.material()),
                "amount", String.valueOf(total),
                "price", String.valueOf(cost),
                "coins", String.valueOf(plugin.store().coins(id)));
        if (dropped > 0) {
            plugin.messages().send(player, "shop-dropped");
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
        return true;
    }

    /* --------------------------------- ключи ---------------------------------- */

    /** Открывает сундук охотника за один ключ семейства. */
    public boolean openChest(Player player, String familyId) {
        if (player == null) {
            return false;
        }
        HuntConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        HuntFamily family = config.family(familyId);
        if (family == null) {
            plugin.messages().send(player, "chest-unknown",
                    "family", familyId == null ? "" : familyId);
            return false;
        }
        HuntChest chest = family.chest();
        if (chest == null) {
            plugin.messages().send(player, "chest-no-chest", "family", family.displayName());
            return false;
        }
        if (!chest.usable()) {
            plugin.messages().send(player, "chest-empty", "family", family.displayName());
            return false;
        }
        UUID id = player.getUniqueId();
        if (plugin.store().keys(id, family.id()) <= 0L) {
            plugin.messages().send(player, "chest-no-key", "key", family.keyName());
            return false;
        }
        if (!plugin.store().spendKey(id, family.id())) {
            plugin.messages().send(player, "chest-no-key", "key", family.keyName());
            return false;
        }

        long now = System.currentTimeMillis();
        ChestResult result = HuntRoll.chest(random, chest);
        long allowed = coinsLeft(id, now, config);
        long coins = Math.min(result.coins(), allowed);
        plugin.store().touchDay(id, player.getName(), now);
        if (coins > 0L) {
            plugin.store().addCoins(id, player.getName(), coins);
        }
        if (result.shards() > 0) {
            plugin.store().addShards(id, player.getName(), family.id(), result.shards());
        }
        int dropped = 0;
        if (result.diamonds() > 0) {
            dropped += payDiamonds(player, result.diamonds());
        }
        int rankBonus = 0;
        for (HuntDrop drop : result.drops()) {
            int extra = rankExtraDrops(player, drop.amount());
            rankBonus += extra;
            dropped += giveItems(player, drop.material(), drop.amount() + extra);
        }
        plugin.requestSave();

        plugin.messages().send(player, "chest-opened",
                "family", family.displayName(),
                "coins", String.valueOf(coins),
                "shards", String.valueOf(result.shards()),
                "shard", family.shardName());
        for (HuntDrop drop : result.drops()) {
            plugin.messages().send(player, "chest-loot-line",
                    "item", itemName(drop.material()),
                    "amount", String.valueOf(drop.amount() + rankExtraDrops(player, drop.amount())));
        }
        if (rankBonus > 0) {
            plugin.messages().send(player, "rank-extra-loot", "amount", String.valueOf(rankBonus));
        }
        if (result.diamonds() > 0) {
            plugin.messages().send(player, "chest-diamonds",
                    "diamonds", String.valueOf(result.diamonds()));
        }
        if (dropped > 0) {
            plugin.messages().send(player, "chest-dropped");
        }
        if (coins < result.coins()) {
            warnCap(player, now, config);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.1f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        return true;
    }

    /* --------------------------------- трофеи --------------------------------- */

    /** Крафтит трофей (или выдаёт уже полученный бесплатно). */
    public boolean craftTrophy(Player player, String trophyId) {
        if (player == null) {
            return false;
        }
        HuntTrophy trophy = plugin.config() == null ? null : plugin.config().trophy(trophyId);
        if (trophy == null) {
            plugin.messages().send(player, "trophy-unknown", "id", trophyId == null ? "" : trophyId);
            return false;
        }
        UUID id = player.getUniqueId();
        if (plugin.store().hasTrophy(id, trophy.id())) {
            return giveTrophy(player, trophy, true);
        }
        long earned = plugin.store().earned(id);
        if (earned < trophy.minCoinsEarned()) {
            plugin.messages().send(player, "trophy-locked",
                    "need", String.valueOf(trophy.minCoinsEarned()),
                    "earned", String.valueOf(earned));
            return false;
        }
        HuntFamily family = plugin.config().family(trophy.familyId());
        String shardName = family == null ? "осколки" : family.shardName();
        long have = plugin.store().shards(id, trophy.familyId());
        if (have < trophy.shards()) {
            plugin.messages().send(player, "trophy-no-shards",
                    "need", String.valueOf(trophy.shards()),
                    "have", String.valueOf(have),
                    "shard", shardName);
            return false;
        }
        long wallet = plugin.store().coins(id);
        if (wallet < trophy.coins()) {
            plugin.messages().send(player, "trophy-no-coins",
                    "need", String.valueOf(trophy.coins()),
                    "coins", String.valueOf(wallet));
            return false;
        }
        if (!plugin.store().spendShards(id, trophy.familyId(), trophy.shards())
                || !plugin.store().spendCoins(id, trophy.coins())) {
            plugin.messages().send(player, "trophy-no-shards",
                    "need", String.valueOf(trophy.shards()),
                    "have", String.valueOf(plugin.store().shards(id, trophy.familyId())),
                    "shard", shardName);
            return false;
        }
        plugin.store().addTrophy(id, player.getName(), trophy.id());
        plugin.requestSave();
        giveTrophy(player, trophy, false);
        plugin.messages().send(player, "trophy-crafted",
                "trophy", trophy.displayName(),
                "shards", String.valueOf(trophy.shards()),
                "coins", String.valueOf(trophy.coins()),
                "shard", shardName);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        return true;
    }

    /** Выдаёт предмет-трофей уже полученного трофея. */
    public boolean claimTrophy(Player player, String trophyId) {
        if (player == null) {
            return false;
        }
        HuntTrophy trophy = plugin.config() == null ? null : plugin.config().trophy(trophyId);
        if (trophy == null) {
            plugin.messages().send(player, "trophy-unknown", "id", trophyId == null ? "" : trophyId);
            return false;
        }
        if (!plugin.store().hasTrophy(player.getUniqueId(), trophy.id())) {
            plugin.messages().send(player, "trophy-not-owned", "trophy", trophy.displayName());
            return false;
        }
        return giveTrophy(player, trophy, true);
    }

    private boolean giveTrophy(Player player, HuntTrophy trophy, boolean claimed) {
        ItemStack item = plugin.items().trophy(trophy);
        int dropped = giveOrDrop(player, item);
        plugin.messages().send(player, claimed ? "trophy-claimed" : "trophy-received",
                "trophy", trophy.displayName());
        if (dropped > 0) {
            plugin.messages().send(player, "trophy-dropped");
        }
        return true;
    }

    /* ------------------------------- отображение ------------------------------- */

    public long coins(Player player) {
        return player == null ? 0L : plugin.store().coins(player.getUniqueId());
    }

    public long earned(Player player) {
        return player == null ? 0L : plugin.store().earned(player.getUniqueId());
    }

    public long shards(Player player, String familyId) {
        return player == null ? 0L : plugin.store().shards(player.getUniqueId(), familyId);
    }

    public long keys(Player player, String familyId) {
        return player == null ? 0L : plugin.store().keys(player.getUniqueId(), familyId);
    }

    public long kills(Player player, String familyId) {
        return player == null ? 0L : plugin.store().kills(player.getUniqueId(), familyId);
    }

    public long killsTotal(Player player) {
        return player == null ? 0L : plugin.store().killsTotal(player.getUniqueId());
    }

    public int trophyCount(Player player) {
        return player == null ? 0 : plugin.store().trophyCount(player.getUniqueId());
    }

    public boolean hasTrophy(Player player, String trophyId) {
        return player != null && plugin.store().hasTrophy(player.getUniqueId(), trophyId);
    }

    public String rankId(long earned) {
        return plugin.config() == null ? "novice" : plugin.config().rankId(earned);
    }

    /** Русское имя звания с MiniMessage — для чата. */
    public String rankText(long earned) {
        return plugin.messages().raw("rank." + rankId(earned));
    }

    /** Сколько монет осталось до следующего звания; 0 — выше некуда. */
    public long coinsToNextRank(long earned) {
        if (plugin.config() == null) {
            return 0L;
        }
        int next = plugin.config().nextRankThreshold(earned);
        return next < 0 ? 0L : Math.max(0L, next - earned);
    }

    /** Название предмета тегом клиента — так оно локализовано у игрока. */
    public static Messages.Trusted itemName(Material material) {
        if (material == null) {
            return Messages.trusted("");
        }
        String path = material.name().toLowerCase(Locale.ROOT);
        String namespace = material.isBlock() ? "block.minecraft." : "item.minecraft.";
        return Messages.trusted("<lang:" + namespace + path + ">");
    }

    /**
     * Кладёт вещи в инвентарь; возвращает количество, которое не влезло и упало
     * под ноги.
     */
    private int giveOrDrop(Player player, ItemStack stack) {
        if (player == null || stack == null) {
            return 0;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        int dropped = 0;
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
            dropped += rest.getAmount();
        }
        return dropped;
    }

    /** Выдаёт {@code total} штук материала стаками; возвращает выброшенное. */
    private int giveItems(Player player, Material material, int total) {
        int remaining = Math.max(0, total);
        int dropped = 0;
        while (remaining > 0) {
            int chunk = Math.min(64, remaining);
            remaining -= chunk;
            dropped += giveOrDrop(player, new ItemStack(material, chunk));
        }
        return dropped;
    }
}
