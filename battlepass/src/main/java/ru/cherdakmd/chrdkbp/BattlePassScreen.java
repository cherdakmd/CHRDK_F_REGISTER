package ru.cherdakmd.chrdkbp;

import net.kyori.adventure.text.Component;
import ru.cherdakmd.chrdkregister.Messages;

/**
 * Сборка строк {@code /battlepass} из шаблонов {@code messages.yml}.
 *
 * <p>Описания заданий и название сезона берутся из конфига и содержат MiniMessage-
 * разметку, поэтому подставляются как {@link Messages#trusted(String)}: обычная
 * подстановка экранирует {@code <}, и в чате появлялись бы literal-теги
 * {@code <gray>…</gray>}. Значения приходят только из конфига администратора —
 * ввода игроков здесь нет.</p>
 */
final class BattlePassScreen {

    /** Ширина полосы прогресса по умолчанию (переопределяется в config.yml). */
    static final int DEFAULT_BAR_WIDTH = 8;

    private static final String FILLED = "▰";
    private static final String EMPTY = "▱";

    private final Messages messages;
    private final int barWidth;

    BattlePassScreen(Messages messages) {
        this(messages, DEFAULT_BAR_WIDTH);
    }

    BattlePassScreen(Messages messages, BattlePassSettings settings) {
        this(messages, settings == null ? DEFAULT_BAR_WIDTH : settings.barWidth());
    }

    private BattlePassScreen(Messages messages, int barWidth) {
        this.messages = messages;
        this.barWidth = Math.max(1, barWidth);
    }

    /** Строка задания с иконкой состояния, прогрессом, наградой и кнопкой. */
    Component mission(String label, BattlePassView.Mission view) {
        BattlePassConfig.MissionDef mission = view.def();
        // desc подставляем последним: если в описании встретится %progress% или
        // другой плейсхолдер, он останется текстом, а не значением.
        Object[] placeholders = {
                "state", state(view.state()),
                "bar", bar(view),
                "progress", view.shownProgress(),
                "target", mission.target(),
                "reward", reward(mission),
                "hint", hint(label, view),
                "desc", described(mission)
        };
        return switch (view.state()) {
            case PROGRESS -> messages.get("mission", placeholders);
            case READY -> messages.get("mission-ready", placeholders);
            case VIP_LOCKED -> messages.get("mission-vip", placeholders);
            case PREMIUM_LOCKED -> messages.get("mission-premium", placeholders);
            case CLAIMED -> messages.get("mission-claimed", placeholders);
        };
    }

    /** Уведомление о том, что задание только что выполнено. */
    Component missionDone(String label, BattlePassConfig.MissionDef mission) {
        // label/id — для старых пользовательских messages.yml, hint/desc — для нового.
        return messages.get("mission-done",
                "label", label, "id", mission.id(),
                "hint", trustedRaw(messages.raw("mission-claim-hint",
                        "label", label, "id", mission.id())),
                "desc", described(mission));
    }

    /** Иконка состояния для строки задания. */
    Messages.Trusted state(BattlePassView.State state) {
        return switch (state) {
            case PROGRESS -> trustedRaw(messages.raw("state-progress"));
            case READY -> trustedRaw(messages.raw("state-ready"));
            case VIP_LOCKED -> trustedRaw(messages.raw("state-vip"));
            case PREMIUM_LOCKED -> trustedRaw(messages.raw("state-premium"));
            case CLAIMED -> trustedRaw(messages.raw("state-claimed"));
        };
    }

    /** Полоса прогресса: закрашенная часть одним цветом, пустая — другим. */
    Messages.Trusted bar(BattlePassView.Mission view) {
        int filled = BattlePassView.filledSegments(view.progress(), view.def().target(), barWidth);
        String bar = messages.raw("bar-filled", "bars", FILLED.repeat(filled))
                + messages.raw("bar-empty", "bars", EMPTY.repeat(barWidth - filled));
        return trustedRaw(bar);
    }

    /**
     * Награды задания: «+1 дн.», «VIP +2 дн.», «★ +3 дн. и товар» — все ветки сразу.
     *
     * <p>Премиум-награда показывается всегда, даже без боевого пропуска: витрина
     * должна объяснять, за что платят. Доступность видна по иконке состояния.</p>
     */
    Messages.Trusted reward(BattlePassConfig.MissionDef mission) {
        if (!mission.hasPremium()) {
            boolean free = mission.freeDays() > 0;
            boolean vip = mission.vipDays() > 0;
            String text;
            if (free && vip) {
                text = messages.raw("reward-both",
                        "free", mission.freeDays(), "vip", mission.vipDays());
            } else if (free) {
                text = messages.raw("reward-free", "days", mission.freeDays());
            } else if (vip) {
                text = messages.raw("reward-vip", "days", mission.vipDays());
            } else {
                return Messages.trusted("");
            }
            return trustedRaw(text);
        }

        StringBuilder text = new StringBuilder();
        if (mission.freeDays() > 0) {
            text.append(messages.raw("reward-free", "days", mission.freeDays()));
        }
        if (mission.vipDays() > 0) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(messages.raw("reward-vip", "days", mission.vipDays()));
        }
        if (text.length() > 0) {
            text.append(' ');
        }
        if (mission.premiumDays() > 0) {
            text.append(messages.raw("reward-premium-days", "days", mission.premiumDays()));
        }
        if (!mission.premiumProducts().isEmpty()) {
            if (mission.premiumDays() > 0) {
                text.append(' ');
            }
            text.append(messages.raw("reward-premium-item",
                    "count", mission.premiumProducts().size()));
        }
        return trustedRaw(text.toString());
    }

    /** Кнопка «забрать» для готовой награды или подсказка «нужен ранг». */
    Messages.Trusted hint(String label, BattlePassView.Mission view) {
        return switch (view.state()) {
            case READY -> trustedRaw(messages.raw("mission-claim-hint",
                    "label", label, "id", view.def().id()));
            case VIP_LOCKED -> trustedRaw(messages.raw("mission-vip-hint"));
            case PREMIUM_LOCKED -> trustedRaw(messages.raw("mission-premium-hint"));
            default -> Messages.trusted("");
        };
    }

    /** Описание задания из конфига: MiniMessage и старые &-коды. */
    static Messages.Trusted described(BattlePassConfig.MissionDef mission) {
        return trustedRaw(mission.description());
    }

    /** То же для произвольной строки из конфига (название сезона, описание задания). */
    static Messages.Trusted described(String text) {
        return trustedRaw(text);
    }

    /**
     * Значение из конфига или из нашего шаблона — уже с разметкой.
     * Старые &-коды поддерживаем для совместимости с прошлыми messages.yml.
     */
    static Messages.Trusted trustedRaw(String miniMessage) {
        return Messages.trusted(Messages.legacyToMini(miniMessage));
    }
}
