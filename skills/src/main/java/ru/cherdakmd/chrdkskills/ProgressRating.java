package ru.cherdakmd.chrdkskills;

/** Прототип рейтинга развития: общий бюджет очков, не прогноз эффективности в бою. */
final class ProgressRating {
    private ProgressRating() { }

    record Result(int totalLevel, int rebirths, int bonusPoints, long score) { }

    static Result calculate(SkillStore.ProgressSnapshot snapshot, SkillCurve.Params curve) {
        int totalLevel = 0;
        for (SkillType skill : SkillType.values()) {
            totalLevel += SkillCurve.levelFor(Math.max(0L, snapshot.xp().getOrDefault(skill, 0L)), curve);
        }
        int rebirths = Math.max(0, snapshot.rebirths());
        int bonusPoints = Math.max(0, snapshot.bonusPoints());
        long score = (long) totalLevel / AttributeMath.LEVELS_PER_POINT
                + (long) AttributeMath.POINTS_PER_REBIRTH * rebirths + bonusPoints;
        return new Result(totalLevel, rebirths, bonusPoints, score);
    }
}
