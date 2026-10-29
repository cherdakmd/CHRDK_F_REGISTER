package ru.cherdakmd.chrdkdaily;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Конфигурация ежедневок: цикл наград по дням стрика (обычная и VIP-ветки), напоминание
 * при входе. Формат предметов в config.yml: {@code "MATERIAL количество"} (? — обрезки
 * допуска).
 */
public final class DailyConfig {

    /** Один предмет награды. */
    public record ItemReward(String material, int amount) {
    }

    /** Награда за день: обычные предметы + VIP (для обладателей активного ранга). */
    public record DayReward(
            int day,
            List<ItemReward> items,
            List<ItemReward> vipItems,
            int vipPassHours
    ) {
        /** Предметы ветки конкретного игрока. */
        public List<ItemReward> itemsFor(boolean vip) {
            return vip && !vipItems.isEmpty() ? vipItems : items;
        }
    }

    private final List<DayReward> rewards;
    private final boolean joinReminder;

    private DailyConfig(List<DayReward> rewards, boolean joinReminder) {
        this.rewards = rewards;
        this.joinReminder = joinReminder;
    }

    /** Дни цикла, как в конфиге (по возрастанию). */
    public List<DayReward> rewards() {
        return rewards;
    }

    public boolean joinReminderEnabled() {
        return joinReminder;
    }

    /** Награда для дня стрика {@code streakDay} (1-based, зациклено по конфигу). */
    public DayReward rewardForStreakDay(int streakDay) {
        if (rewards.isEmpty()) {
            return new DayReward(streakDay, List.of(), List.of(), 0);
        }
        int index = Math.floorMod(streakDay - 1, rewards.size());
        return rewards.get(index);
    }

    /** Парсинг; validateMaterials=false — для юнит-тестов (Material не грузится вне сервера). */
    public static DailyConfig parse(FileConfiguration config, Logger logger, boolean validateMaterials) {
        List<DayReward> rewards = new ArrayList<>();
        int expectedDay = 1;
        for (var raw : config.getMapList("rewards")) {
            int day = intOf(raw.get("day"), expectedDay);
            List<ItemReward> items = parseItems(raw.get("items"), logger, validateMaterials);
            List<ItemReward> vipItems = parseItems(raw.get("vip-items"), logger, validateMaterials);
            // 1.4.184: награда в часах, одна награда — не больше суток. Старый ключ
            // vip-pass-days читается как часы, чтобы конфиг без перезаписи не потерял награду.
            Object hoursRaw = raw.containsKey("vip-boost-hours") ? raw.get("vip-boost-hours") : raw.containsKey("vip-pass-hours")
                    ? raw.get("vip-pass-hours") : raw.get("vip-pass-days");
            int vipPassHours = Math.min(24, Math.max(0, intOf(hoursRaw, 0)));
            if (items.isEmpty() && vipItems.isEmpty() && vipPassHours == 0) {
                logger.warning("[DailyRewards] rewards день " + day + ": пустая награда — пропускаю.");
                continue;
            }
            rewards.add(new DayReward(day, items, vipItems, vipPassHours));
            expectedDay = day + 1;
        }
        if (rewards.isEmpty()) {
            logger.warning("[DailyRewards] rewards пуст — наград не будет (проверьте config.yml).");
        }
        return new DailyConfig(List.copyOf(rewards),
                config.getBoolean("join-reminder", true));
    }

    private static List<ItemReward> parseItems(Object value, Logger logger, boolean validateMaterials) {
        if (!(value instanceof List<?> list)) return List.of();
        List<ItemReward> items = new ArrayList<>();
        for (Object entry : list) {
            String text = String.valueOf(entry).trim();
            String[] parts = text.split("\\s+");
            String material = parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : "";
            int amount = parts.length > 1 ? intOf(parts[1], 1) : 1;
            Material bukkit = validateMaterials ? Material.matchMaterial(material) : null;
            if (material.isBlank() || amount <= 0
                    || (validateMaterials && (bukkit == null || !bukkit.isItem()))) {
                logger.warning("[DailyRewards] rewards: пропускаю непонятный предмет '" + text + "'.");
                continue;
            }
            items.add(new ItemReward(bukkit==null?material:bukkit.name(), Math.min(2304, amount)));
        }
        return List.copyOf(items);
    }

    /** Краткое описание награды для лора календаря. */
    public static String describe(DayReward reward, boolean vip, Optional<Integer> fallbackPassHours) {
        List<String> parts = new ArrayList<>();
        reward.itemsFor(vip).forEach(item ->
                parts.add(DailyStore.moneyUnit(item.material())>0?"/bal +"+(long)DailyStore.moneyUnit(item.material())*item.amount()+" 💠":item.material().toLowerCase(Locale.ROOT) + " ×" + item.amount()));
        int passHours = vip ? reward.vipPassHours() : 0;
        if (passHours <= 0) {
            passHours = fallbackPassHours.orElse(0);
        }
        if (passHours > 0) {
            parts.add("+" + passHours + " ч. буста");
        }
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
