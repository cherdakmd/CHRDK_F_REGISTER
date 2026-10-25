package ru.cherdakmd.chrdkbp;

/**
 * Правила премиум-трека — чистая логика без Bukkit.
 *
 * <p>Боевой пропуск — это товар каталога ядра ({@code battlepass-premium} по
 * умолчанию), купленный на витрине. Пока срок товара не истёк, игрок может
 * забирать премиум-награды заданий; срок и продление считает каталог, а не
 * батлпасс, поэтому сезон и подписка живут независимо.</p>
 *
 * <p>Награды премиум-трека не дают игровой силы: это дни ранга и товары
 * каталога (косметика, оформление) — те же, что продаются поштучно.</p>
 */
final class BattlePassPremium {

    private BattlePassPremium() {
    }

    /**
     * Активен ли боевой пропуск.
     *
     * @param enabled   секция {@code battlepass.premium.enabled}
     * @param until     срок товара в миллисекундах Unix (0 — не куплен)
     * @param nowMillis текущее время
     */
    static boolean active(boolean enabled, long until, long nowMillis) {
        return enabled && until > nowMillis;
    }

    /** Есть ли у сезона хоть одна премиум-награда. */
    static boolean anyRewards(BattlePassConfig.Season season) {
        if (season == null) {
            return false;
        }
        for (BattlePassConfig.WeekDef week : season.weeks()) {
            for (BattlePassConfig.MissionDef mission : week.missions()) {
                if (mission.hasPremium()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Сколько заданий предлагают премиум-награду (для сводок и логов). */
    static int missionsWithRewards(BattlePassConfig.Season season) {
        if (season == null) {
            return 0;
        }
        int count = 0;
        for (BattlePassConfig.WeekDef week : season.weeks()) {
            for (BattlePassConfig.MissionDef mission : week.missions()) {
                if (mission.hasPremium()) {
                    count++;
                }
            }
        }
        return count;
    }
}
