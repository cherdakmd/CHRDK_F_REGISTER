package ru.cherdakmd.chrdkgoals;

/** Шаблон общей цели: ид, метрика прогресса, дефолтный объём и дефолтный пул (алмазов). */
public record GoalTemplate(String id, GoalMetric metric, int defaultTarget, int defaultRewardPool) {

    public static final GoalTemplate[] ALL = {
            new GoalTemplate("hostile-slay", GoalMetric.HOSTILE_SLAY, 1500, 96),
            new GoalTemplate("ore-break", GoalMetric.ORE_BREAK, 4000, 128),
            new GoalTemplate("blocks-travelled", GoalMetric.BLOCKS_TRAVELLED, 400_000, 64),
            new GoalTemplate("blocks-placed", GoalMetric.BLOCKS_PLACED, 20_000, 64),
            new GoalTemplate("crops-harvested", GoalMetric.CROPS_HARVESTED, 6000, 48),
            new GoalTemplate("fish-caught", GoalMetric.FISH_CAUGHT, 1500, 48),
            new GoalTemplate("blocks-broken", GoalMetric.BLOCKS_BROKEN, 50_000, 96),
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
