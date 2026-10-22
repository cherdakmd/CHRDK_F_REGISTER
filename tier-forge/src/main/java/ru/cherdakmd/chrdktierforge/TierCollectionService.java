package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Коллекция кузни: отмечает, что игрок впервые увидел, объявляет новинки и
 * выплачивает награды за вехи (1.4.150).
 *
 * <p>Вызывается там, где предмет окончательно получил тир: крафт (старый
 * флоу), опознание, дроп с моба и результат наковальни. В режиме неопознанных
 * предметов крафт ничего не записывает — редкость ещё скрыта, и «коллекция по
 * превью» была бы бесплатной разведкой.</p>
 *
 * <p>Награда за веху выдаётся ровно один раз: отметка {@code claimed} пишется
 * в книгу вместе с прогрессом, поэтому сбой между выплатой и сохранением не
 * удвоит дни проходки.</p>
 */
public final class TierCollectionService {

    private final TierForgePlugin plugin;
    private final TierCollectionKeeper keeper;

    public TierCollectionService(TierForgePlugin plugin, TierCollectionKeeper keeper) {
        this.plugin = plugin;
        this.keeper = keeper;
    }

    /** Отмечает предмет, если он тир-предмет и коллекция включена. */
    public void record(Player player, ItemStack item) {
        if (player == null || item == null) {
            return;
        }
        TierCollectionConfig config = config();
        if (config == null || !config.enabled()) {
            return;
        }
        String tier = plugin.items().tierOf(item);
        if (tier == null) {
            return;
        }
        List<String> properties = new ArrayList<>();
        for (TierProperty property : plugin.items().propertiesOf(item)) {
            properties.add(property.id());
        }
        List<String> affixes = new ArrayList<>();
        for (TierAffix affix : plugin.items().affixesOf(item)) {
            affixes.add(affix.id());
        }
        TierCollectionBook.Discovery discovery = keeper.record(
                player.getUniqueId(), tier, properties, affixes);
        if (discovery.isEmpty()) {
            return;
        }
        if (config.announceFinds()) {
            announce(player, discovery);
        }
        grantMilestones(player, config);
    }

    /** Снимок книги игрока — для GUI и текстовой сводки. */
    public TierCollectionBook.Snapshot snapshot(Player player) {
        return keeper.snapshot(player == null ? null : player.getUniqueId());
    }

    /** Снимок по UUID — нужен админской статистике офлайн-игрока. */
    public TierCollectionBook.Snapshot snapshot(UUID player) {
        return keeper.snapshot(player);
    }

    public TierCollectionKeeper keeper() {
        return keeper;
    }

    public TierCollectionConfig config() {
        TierForgeConfig config = plugin.config();
        return config == null ? null : config.collection();
    }

    public boolean enabled() {
        TierCollectionConfig config = config();
        return config != null && config.enabled();
    }

    /* ------------------------------- сообщения ------------------------------- */

    private void announce(Player player, TierCollectionBook.Discovery discovery) {
        List<String> parts = new ArrayList<>(3);
        if (discovery.tier() != null) {
            parts.add(plugin.messages().raw("collection-part-tier",
                    "tier", Messages.trusted(plugin.messages()
                            .raw("tier." + discovery.tier() + ".name"))));
        }
        if (!discovery.properties().isEmpty()) {
            parts.add(plugin.messages().raw("collection-part-properties",
                    "count", discovery.properties().size()));
        }
        if (!discovery.affixes().isEmpty()) {
            parts.add(plugin.messages().raw("collection-part-affixes",
                    "count", discovery.affixes().size()));
        }
        String joined = String.join(plugin.messages().raw("collection-part-separator"), parts);
        plugin.messages().send(player, "collection-found",
                "parts", Messages.trusted(joined));
    }

    /* -------------------------------- награды -------------------------------- */

    private void grantMilestones(Player player, TierCollectionConfig config) {
        TierCollectionBook.Snapshot snapshot = snapshot(player);
        if (snapshot.tierCount() >= TierCollectionBook.TIER_TOTAL) {
            claim(player, "tiers", config.tiers(),
                    plugin.messages().raw("collection-milestone-tiers"));
        }
        for (TierCollectionConfig.Milestone milestone : config.properties()) {
            if (snapshot.propertyCount() >= milestone.count()) {
                claim(player, "properties:" + milestone.count(), milestone,
                        plugin.messages().raw("collection-milestone-properties",
                                "count", milestone.count()));
            }
        }
        for (TierCollectionConfig.Milestone milestone : config.affixes()) {
            if (snapshot.affixCount() >= milestone.count()) {
                claim(player, "affixes:" + milestone.count(), milestone,
                        plugin.messages().raw("collection-milestone-affixes",
                                "count", milestone.count()));
            }
        }
    }

    private void claim(Player player, String key, TierCollectionConfig.Milestone milestone,
                       String name) {
        if (!keeper.claim(player.getUniqueId(), key)) {
            return; // веха уже выплачена
        }
        pay(player, key, milestone);
        plugin.messages().send(player, "collection-milestone",
                "name", Messages.trusted(name),
                "reward", Messages.trusted(rewardText(milestone)));
    }

    private void pay(Player player, String key, TierCollectionConfig.Milestone milestone) {
        if (milestone.passDays() > 0) {
            if (plugin.coreApi() != null) {
                plugin.coreApi().grantPass(player.getName(), milestone.passDays(),
                        "tierforge:collection:" + key);
            } else {
                plugin.getLogger().warning("Ядро недоступно: награда коллекции " + key
                        + " не выдана игроку " + player.getName());
            }
        }
        int remaining = milestone.diamonds();
        while (remaining > 0) {
            int amount = Math.min(64, remaining);
            remaining -= amount;
            Map<Integer, ItemStack> leftover = player.getInventory()
                    .addItem(new ItemStack(Material.DIAMOND, amount));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    /** Человеческий текст награды для чата. */
    String rewardText(TierCollectionConfig.Milestone milestone) {
        List<String> parts = new ArrayList<>(2);
        if (milestone.passDays() > 0) {
            parts.add(plugin.messages().raw("collection-reward-days",
                    "days", milestone.passDays()));
        }
        if (milestone.diamonds() > 0) {
            parts.add(plugin.messages().raw("collection-reward-diamonds",
                    "count", milestone.diamonds()));
        }
        if (parts.isEmpty()) {
            return plugin.messages().raw("collection-reward-none");
        }
        return String.join(plugin.messages().raw("collection-reward-join"), parts);
    }
}
