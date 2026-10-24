package ru.cherdakmd.chrdkclasses;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI: {@code %chrdkclasses_...%} (1.4.164).
 *
 * <p>Только чтение готового состояния: таб и скорборд спрашивают их часто.
 * У игрока без класса — пустая строка, а не «0», чтобы условие в скорборде не
 * срабатывало ложно. Уровень и очки считаются по навыкам, поэтому для
 * оффлайн-игрока они равны нулю: прокачка подтянется, когда игрок зайдёт.</p>
 */
public final class ClassesPlaceholders extends PlaceholderExpansion {

    private final ClassesPlugin plugin;

    ClassesPlaceholders(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkclasses";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        TalentService service = plugin.service();
        if (service == null) {
            return "";
        }
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("enabled")) {
            return plugin.config().enabled() ? "true" : "false";
        }
        UUID id = player == null ? null : player.getUniqueId();
        PlayerClass playerClass = id == null ? null : service.classOf(id);
        if (key.equals("skills")) {
            return plugin.skillsBridge().available() ? "true" : "false";
        }
        if (id == null) {
            return "";
        }
        Map<EffectKind, Double> effects = service.effects(id);
        return switch (key) {
            case "class" -> playerClass == null ? ""
                    : plugin.messages().plain("class." + playerClass.id() + ".name");
            case "class_id" -> playerClass == null ? "" : playerClass.id();
            case "level" -> playerClass == null ? "" : Integer.toString(service.level(id));
            case "points" -> playerClass == null ? "" : Integer.toString(service.availablePoints(id));
            case "spent" -> playerClass == null ? "" : Integer.toString(service.spentPoints(id));
            case "talents" -> playerClass == null ? "" : Integer.toString(plugin.store().talentCount(id));
            case "skills_total" -> playerClass == null ? "" : Integer.toString(service.totalSkill(id));
            case "next" -> playerClass == null ? ""
                    : Integer.toString(ClassMath.nextLevelIn(service.totalSkill(id),
                    plugin.config().levelsPerLevel()));
            case "health" -> number(effects, EffectKind.MAX_HEALTH);
            case "armor" -> number(effects, EffectKind.ARMOR);
            case "melee" -> number(effects, EffectKind.MELEE_DAMAGE);
            case "arrow" -> number(effects, EffectKind.ARROW_DAMAGE);
            case "reduction" -> number(effects, EffectKind.DAMAGE_REDUCTION);
            case "speed" -> number(effects, EffectKind.MOVEMENT_SPEED);
            case "regen" -> number(effects, EffectKind.REGEN);
            case "change_left" -> playerClass == null ? ""
                    : Long.toString(service.changeCooldownLeft(id, System.currentTimeMillis()));
            case "respec_left" -> playerClass == null ? ""
                    : Long.toString(service.respecCooldownLeft(id, System.currentTimeMillis()));
            default -> "";
        };
    }

    /** Эффект в единицах своего вида: проценты без «%», сердца — в сердцах. */
    private String number(Map<EffectKind, Double> effects, EffectKind kind) {
        double value = effects.getOrDefault(kind, 0.0);
        return value <= 0.0 ? "" : ClassMath.amount(kind, value);
    }
}
