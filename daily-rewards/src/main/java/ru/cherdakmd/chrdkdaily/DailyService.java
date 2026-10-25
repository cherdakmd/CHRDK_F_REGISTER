package ru.cherdakmd.chrdkdaily;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Выдача наград: apply конфиг-день к игроку (предметы в инвентарь + drop под ноги,
 * VIP-дни проходки — через API ядра).
 */
public final class DailyService {

    private final DailyRewardsPlugin plugin;
    private final DailyStore store;

    public DailyService(DailyRewardsPlugin plugin, DailyStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public DailyStore store() {
        return store;
    }

    /** Итог claim: стрик, что выдано, откуда VIP. */
    public record ClaimResult(int streak, List<DailyConfig.ItemReward> items, int passDays, boolean vip) {
    }

    /**
     * Забирает сегодняшнюю награду (игрок ДОЛЖЕН быть авторизован — проверяется вызывающим).
     * Возвращает null, если сегодня уже брал.
     */
    public ClaimResult claim(Player player) {
        ChrdkRegisterApi api = plugin.coreApi();
        long now = System.currentTimeMillis();
        int streak = store.claim(player.getUniqueId(), now);
        if (streak < 0) return null;

        DailyConfig.DayReward day = plugin.config().rewardForStreakDay(streak);
        int vipRank = Math.max(1, plugin.getConfig().getInt("vip.min-rank", 2));
        boolean vip = api != null && api.rankLevel(player.getName()) >= vipRank;
        List<DailyConfig.ItemReward> items = day.itemsFor(vip);
        for (DailyConfig.ItemReward reward : items) {
            Material material = Material.matchMaterial(reward.material());
            if (material != null) {
                giveItems(player, material, reward.amount());
            }
        }
        int passDays = vip ? day.vipPassDays() : 0;
        if (passDays > 0 && api != null) {
            api.grantPass(player.getName(), passDays, "daily-rewards:day" + day.day());
        }
        return new ClaimResult(streak, items, passDays, vip);
    }

    /** Игрок может взять награду сегодня. */
    public boolean canClaim(Player player) {
        return store.canClaim(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Выдача предметов с переносом излишка. */
    public static void giveItems(Player player, Material material, int amount) {
        int remaining = amount;
        Map<Integer, org.bukkit.inventory.ItemStack> leftover = new HashMap<>();
        while (remaining > 0) {
            int take = Math.min(material.getMaxStackSize(), remaining);
            leftover.putAll(player.getInventory().addItem(new org.bukkit.inventory.ItemStack(material, take)));
            remaining -= take;
        }
        leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    /** Человеческий текст набора предметов для сообщений. */
    public static String itemsText(List<DailyConfig.ItemReward> items) {
        List<String> parts = new ArrayList<>();
        items.forEach(item -> parts.add(item.material().toLowerCase(java.util.Locale.ROOT)
                + " ×" + item.amount()));
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }
}
