package ru.cherdakmd.chrdkclasses;

import net.kyori.adventure.text.Component;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Общий текст паспорта и /class points: одна арифметика и одинаковые причины отказа. */
final class ClassPassportText {
    private ClassPassportText() { }

    static List<Component> progress(Messages messages, LegacyClassSnapshotService.Result snapshot, int spent) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("passport-mode-skills"));
        for (LegacyClassSnapshotService.Source source : LegacyClassSnapshotService.Source.values()) {
            ClassDataRead read = snapshot.sources().get(source);
            String name = messages.raw("passport-source-" + source.name().toLowerCase(Locale.ROOT));
            String status = read.status() == ClassDataRead.Status.OK ? String.valueOf(read.value())
                    : messages.raw("passport-status-" + read.status().name().toLowerCase(Locale.ROOT));
            lines.add(messages.get("passport-source-line", "source", Messages.trusted(name),
                    "state", Messages.trusted(status)));
        }
        if (!snapshot.ready()) {
            lines.add(messages.get("passport-data-unavailable"));
            lines.add(messages.get("passport-spent", "spent", String.valueOf(spent)));
            return lines;
        }
        LegacyClassBasis basis = snapshot.basis();
        if (!basis.rules().ranksEnabled()) {
            lines.add(messages.get("info-rank-off"));
        } else if (basis.rank() == 0) {
            lines.add(messages.get("info-rank-none"));
        } else {
            RankPerks perks = RankPerks.of(basis.rank());
            lines.add(messages.get("info-rank", "level", String.valueOf(perks.level()),
                    "skill", String.valueOf(perks.thresholdDiscount()), "step", String.valueOf(perks.levelStepDiscount()),
                    "cooldown", String.valueOf(Math.round((1.0 - perks.cooldownFactor()) * 100.0)),
                    "change", String.valueOf(perks.changeDaysOff()), "respec", String.valueOf(perks.respecHoursOff())));
        }
        lines.add(messages.get("passport-level", "level", String.valueOf(basis.level()),
                "max", "34", "total", String.valueOf(basis.skillTotal())));
        if (basis.level() == 34) {
            lines.add(messages.get("passport-level-cap"));
        } else {
            lines.add(messages.get("passport-next", "next", String.valueOf(basis.nextLevelIn()),
                    "reward", String.valueOf(basis.rules().pointsPerLevel())));
        }
        lines.add(messages.get("passport-level-points", "points", String.valueOf(basis.levelPoints()),
                "start", String.valueOf(basis.rules().startingPoints()),
                "per", String.valueOf(basis.rules().pointsPerLevel()),
                "step", String.valueOf(basis.rules().levelStep(basis.rank()))));
        lines.add(messages.get("passport-achievement-points", "points", String.valueOf(basis.achievementPoints()),
                "max", String.valueOf(basis.rules().achievementMax())));
        lines.add(messages.get("passport-earned", "earned", String.valueOf(basis.earnedPoints())));
        lines.add(messages.get("passport-spent", "spent", String.valueOf(spent)));
        lines.add(messages.get("passport-free", "free", String.valueOf(basis.freePoints())));
        if (basis.deficit() > 0) {
            lines.add(messages.get("passport-deficit", "deficit", String.valueOf(basis.deficit())));
        }
        return lines;
    }

    static List<Component> effect(Messages messages, ClassEffectView view) {
        String unitKey = switch (view.kind().unit()) {
            case HALF_HEARTS -> "passport-unit-hearts";
            case PERCENT -> "passport-unit-percent";
            case POINTS -> "passport-unit-points";
        };
        Object unit = Messages.trusted(messages.raw(unitKey));
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("passport-effect-base", "value", ClassMath.amount(view.kind(), view.passive()), "unit", unit));
        lines.add(messages.get("passport-effect-talents", "value", ClassMath.amount(view.kind(), view.talents()), "unit", unit));
        lines.add(messages.get("passport-effect-raw", "value", ClassMath.amount(view.kind(), view.raw()), "unit", unit));
        lines.add(messages.get("passport-effect-effective", "value", ClassMath.amount(view.kind(), view.effective()), "unit", unit));
        lines.add(messages.get("passport-effect-cap", "value", ClassMath.amount(view.kind(), view.ceiling()), "unit", unit));
        if (view.clipped() > 0) {
            lines.add(messages.get("passport-effect-clipped", "value", ClassMath.amount(view.kind(), view.clipped()), "unit", unit));
        }
        return lines;
    }
}
