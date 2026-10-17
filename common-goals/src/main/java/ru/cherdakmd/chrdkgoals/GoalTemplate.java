package ru.cherdakmd.chrdkgoals;

/** Шаблон общей цели: ид, метрика прогресса, дефолтный объём и дефолтный пул (алмазов). */
public record GoalTemplate(String id, GoalMetric metric, int defaultTarget, int defaultRewardPool) {

    public static final GoalTemplate[] ALL = {
            // — бой и охота —
            new GoalTemplate("hostile-slay", GoalMetric.HOSTILE_SLAY, 1500, 96),
            new GoalTemplate("passive-slay", GoalMetric.PASSIVE_SLAY, 3000, 64),
            new GoalTemplate("boss-slay", GoalMetric.BOSS_SLAY, 10, 160),
            new GoalTemplate("bow-shoot", GoalMetric.BOW_SHOOT, 3000, 32),
            new GoalTemplate("raid-win", GoalMetric.RAID_WIN, 6, 128),
            // — шахтёрство и стройка —
            new GoalTemplate("ore-break", GoalMetric.ORE_BREAK, 4000, 128),
            new GoalTemplate("blocks-broken", GoalMetric.BLOCKS_BROKEN, 50_000, 96),
            new GoalTemplate("wood-chop", GoalMetric.WOOD_CHOP, 25_000, 80),
            new GoalTemplate("ground-dig", GoalMetric.GROUND_DIG, 20_000, 48),
            new GoalTemplate("blocks-placed", GoalMetric.BLOCKS_PLACED, 20_000, 64),
            // — ферма и животные —
            new GoalTemplate("crops-harvested", GoalMetric.CROPS_HARVESTED, 6000, 48),
            new GoalTemplate("animal-breed", GoalMetric.ANIMAL_BREED, 800, 48),
            new GoalTemplate("animal-tame", GoalMetric.ANIMAL_TAME, 100, 40),
            new GoalTemplate("fish-caught", GoalMetric.FISH_CAUGHT, 1500, 48),
            // — магия и ремесло —
            new GoalTemplate("enchant-items", GoalMetric.ENCHANT_ITEMS, 400, 80),
            new GoalTemplate("potion-brew", GoalMetric.POTION_BREW, 500, 64),
            new GoalTemplate("item-smelt", GoalMetric.ITEM_SMELT, 6000, 48),
            new GoalTemplate("item-craft", GoalMetric.ITEM_CRAFT, 12_000, 48),
            new GoalTemplate("pearl-throw", GoalMetric.PEARL_THROW, 500, 32),
            // — мир и быт —
            new GoalTemplate("blocks-travelled", GoalMetric.BLOCKS_TRAVELLED, 400_000, 64),
            new GoalTemplate("villager-trade", GoalMetric.VILLAGER_TRADE, 1500, 64),
            new GoalTemplate("xp-collect", GoalMetric.XP_COLLECT, 80_000, 80),
            new GoalTemplate("bed-sleep", GoalMetric.BED_SLEEP, 250, 24),
            // — общий котёл —
            new GoalTemplate("donate-diamonds", GoalMetric.DONATE_DIAMONDS, 2000, 192),
    };

    public static GoalTemplate byId(String id) {
        if (id == null) {
            return null;
        }
        for (GoalTemplate template : ALL) {
            if (template.id().equals(id)) {
                return template;
            }
        }
        return null;
    }
}
