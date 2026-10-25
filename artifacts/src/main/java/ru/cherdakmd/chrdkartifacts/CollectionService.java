package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Коллекция артефактов (1.4.130): учёт открытий и выдача наград за пороги.
 *
 * <p>Открытие засчитывается, когда артефакт попадает в инвентарь игрока:
 * подбор с земли, выдача администратором, находка в сундуке (подбор),
 * а также разовый скан инвентаря при входе и открытии коллекции — так предметы,
 * полученные до 1.4.130, не приходится добывать заново.</p>
 *
 * <p>Награда — дни ранга через API ядра и алмазы предметами. Награда
 * выдаётся ровно один раз на порог: отметка живёт в {@link CollectionStore},
 * поэтому перезапуск и повторный вход не дублируют приз.</p>
 */
public final class CollectionService {

    private final ArtifactsPlugin plugin;
    private final CollectionStore store;

    public CollectionService(ArtifactsPlugin plugin) {
        this.plugin = plugin;
        this.store = new CollectionStore(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.store.load();
    }

    public boolean enabled() {
        return plugin.config().collection().enabled();
    }

    public int total() {
        return ArtifactRegistry.size();
    }

    public int count(Player player) {
        return player == null ? 0 : store.count(key(player));
    }

    public int percent(Player player) {
        return CollectionRewards.percentOf(count(player), total());
    }

    public boolean isDiscovered(Player player, String artifactId) {
        return player != null && store.isDiscovered(key(player), artifactId);
    }

    public Set<String> discovered(Player player) {
        return player == null ? Set.of() : store.discovered(key(player));
    }

    public Set<Integer> claimed(Player player) {
        return player == null ? Set.of() : store.claimed(key(player));
    }

    /**
     * Отмечает артефакт открытым и, если он новый, поздравляет игрока
     * и проверяет награды за пороги.
     *
     * @return {@code true} — артефакт открыт впервые
     */
    public boolean record(Player player, String artifactId) {
        if (player == null || !enabled()) {
            return false;
        }
        ArtifactDef def = ArtifactRegistry.byId(artifactId);
        if (def == null) {
            return false;
        }
        if (!store.discover(key(player), def.id(), System.currentTimeMillis())) {
            return false;
        }
        int found = count(player);
        plugin.messages().send(player, "collection-discovered",
                "name", Messages.stripFormatting(plugin.items().nameOf(def)),
                "found", String.valueOf(found),
                "total", String.valueOf(total()));
        if (plugin.feedback() != null) {
            plugin.feedback().discovered(player);
        }
        if (found >= total()) {
            plugin.messages().send(player, "collection-done",
                    "total", String.valueOf(total()));
        }
        claim(player);
        return true;
    }

    /**
     * Сканирует инвентарь и засчитывает найденные артефакты одним сообщением.
     *
     * @return сколько артефактов открыто впервые
     */
    public int scan(Player player) {
        if (player == null || !enabled()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int fresh = 0;
        for (ItemStack item : inventoryItems(player)) {
            String id = plugin.items().idOf(item);
            if (id != null && store.discover(key(player), id, now)) {
                fresh++;
            }
        }
        if (fresh == 0) {
            return 0;
        }
        int found = count(player);
        plugin.messages().send(player, "collection-scan",
                "count", String.valueOf(fresh),
                "found", String.valueOf(found),
                "total", String.valueOf(total()));
        if (fresh > 0 && plugin.feedback() != null) {
            plugin.feedback().discovered(player);
        }
        claim(player);
        return fresh;
    }

    /** Выдаёт все достигнутые, но ещё не полученные награды. */
    public int claim(Player player) {
        if (player == null || !enabled()) {
            return 0;
        }
        List<CollectionRewards.Tier> tiers = CollectionRewards.claimable(
                plugin.config().collection().rewards(), store.claimed(key(player)),
                count(player), total());
        int granted = 0;
        for (CollectionRewards.Tier tier : tiers) {
            if (!store.markClaimed(key(player), tier.percent())) {
                continue;
            }
            grant(player, tier);
            plugin.messages().send(player, "collection-reward-granted",
                    "percent", String.valueOf(tier.percent()),
                    "reward", Messages.trusted(rewardText(tier)));
            granted++;
        }
        return granted;
    }

    /** Текст награды для GUI и чата (MiniMessage, собран из messages.yml). */
    public String rewardText(CollectionRewards.Tier tier) {
        List<String> parts = new ArrayList<>(2);
        if (tier.passDays() > 0) {
            parts.add(plugin.messages().raw("collection-reward-days",
                    "days", String.valueOf(tier.passDays())));
        }
        if (tier.diamonds() > 0) {
            parts.add(plugin.messages().raw("collection-reward-diamonds",
                    "count", String.valueOf(tier.diamonds())));
        }
        if (parts.isEmpty()) {
            return plugin.messages().raw("collection-reward-none");
        }
        return String.join(plugin.messages().raw("collection-reward-join"), parts);
    }

    /** Полоса прогресса для GUI. */
    public String progressBar(Player player) {
        return CollectionRewards.bar(percent(player), 10);
    }

    public boolean isDirty() {
        return store.isDirty();
    }

    public void saveNow() {
        store.saveNow();
    }

    public void flush() {
        store.flush();
    }

    private void grant(Player player, CollectionRewards.Tier tier) {
        if (tier.passDays() > 0) {
            if (plugin.coreApi() != null) {
                plugin.coreApi().grantPass(player.getName(), tier.passDays(),
                        "artifacts:collection_" + tier.percent());
            } else {
                plugin.getLogger().warning("Ядро недоступно: награда за коллекцию "
                        + tier.percent() + "% (дни ранга) не выдана игроку "
                        + player.getName() + ".");
            }
        }
        // Алмазы выдаются стаками: ItemStack не принимает количество больше 64,
        // а конфиг допускает до 576 — без разбивки это был бы IllegalArgumentException.
        int remaining = tier.diamonds();
        while (remaining > 0) {
            int amount = Math.min(64, remaining);
            remaining -= amount;
            ItemStack stack = new ItemStack(Material.DIAMOND, amount);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    private static List<ItemStack> inventoryItems(Player player) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null) {
                out.add(item);
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null) {
                out.add(item);
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null) {
            out.add(off);
        }
        return out;
    }

    private static String key(Player player) {
        return player.getUniqueId().toString();
    }
}
