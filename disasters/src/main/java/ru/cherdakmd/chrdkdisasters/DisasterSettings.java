package ru.cherdakmd.chrdkdisasters;

/**
 * Четыре группы настроек третьей итерации: награды, прокачка, интеграции и
 * магазин. Вынесены из {@link DisastersConfig} отдельными record, чтобы снимок
 * конфига не превращался в список из четырёх десятков позиционных аргументов.
 */
public final class DisasterSettings {

    private DisasterSettings() {
    }

    /** Осколки: база, надбавка за уровень катастрофы, дневной лимит, потолок бонуса. */
    public record Rewards(boolean enabled, int base, int perLevel,
                          int dailyCap, double bonusCap) {
    }

    /** Стойкость: база опыта, опыт за событие и за уровень катастрофы, потолок. */
    public record Progression(int baseXp, int xpPerSurvive, int xpPerLevel, int maxLevel) {
    }

    /** Мягкие интеграции: ачивки CHRDK_Achievements и опыт CHRDK_Skills. */
    public record Integrations(boolean achievements, boolean skills,
                               String skillId, long units) {
    }

    /** Opt-in разрушительный режим: визуальный размах не требует включать урон. */
    public record Cataclysm(boolean enabled, boolean titles, boolean damage,
                            boolean knockback, int maxFireSeconds,
                            int maxAffectedPlayers, double maxDamagePerPulse) {
    }

    /** Усиление природного ландшафта; Claims/spawn проверяются независимо на каждый блок. */
    public record Terrain(boolean enabled, int radiusBonus, int depthMultiplier,
                          int maxBlocksPerPulse, boolean collapses, boolean wildfire) {
    }

    /** Три товара /disasters shop: алмазы на счёт, припасы и пропуск катастроф. */
    public record Shop(int diamondsPrice, int diamondsAmount,
                       int suppliesPrice, int suppliesDiamonds,
                       int wardPrice, int wardMinutes) {
    }
}
