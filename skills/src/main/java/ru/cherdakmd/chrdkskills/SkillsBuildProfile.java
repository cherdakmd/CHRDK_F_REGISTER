package ru.cherdakmd.chrdkskills;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Публичные очки сборки и текущие атрибуты онлайн-игрока, не расчёт урона. */
final class SkillsBuildProfile implements ChrdkRegisterApi.ProfileProvider {
    private final SkillsPlugin plugin;

    SkillsBuildProfile(SkillsPlugin plugin) { this.plugin = plugin; }

    @Override public int order() { return 41; }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || context.playerId() == null || plugin.store() == null) return null;
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        if (plugin.skills() != null) {
            plugin.store().progressSnapshot(context.playerId().toString()).ifPresent(snapshot -> {
                ProgressRating.Result rating = ProgressRating.calculate(snapshot, plugin.skills().curve());
                lines.add(messages.get("profile-rating", "score", rating.score()));
                lines.add(messages.get("profile-rating-formula", "level", rating.totalLevel(),
                        "rebirths", rating.rebirths(), "bonus", rating.bonusPoints()));
                lines.add(messages.get("profile-rating-note"));
            });
        }
        plugin.store().buildSnapshot(context.playerId().toString()).ifPresent(build -> {
            lines.add(messages.get("profile-build-attributes"));
            for (PlayerAttribute attribute : PlayerAttribute.values()) {
                lines.add(messages.get("profile-build-attribute", "name", attribute.displayName(),
                        "value", build.attributes().get(attribute)));
            }
            lines.add(messages.get("profile-build-branches"));
            for (BuildBranch branch : BuildBranch.values()) {
                lines.add(messages.get("profile-build-branch", "name", branch.displayName(),
                        "level", build.branches().get(branch), "max", BuildBranch.MAX_LEVEL));
            }
        });
        Player player = context.online() ? Bukkit.getPlayer(context.playerId()) : null;
        if (player != null && player.isOnline()) {
            lines.add(messages.get("profile-combat-heading"));
            addAttribute(lines, messages, player, Attribute.MAX_HEALTH, "profile-combat-health");
            addAttribute(lines, messages, player, Attribute.ARMOR, "profile-combat-armor");
            addAttribute(lines, messages, player, Attribute.ATTACK_DAMAGE, "profile-combat-attack");
            addAttribute(lines, messages, player, Attribute.ATTACK_SPEED, "profile-combat-speed");
            lines.add(messages.get("profile-combat-note"));
        }
        if (lines.isEmpty()) return null;
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-build-title"), Material.IRON_SWORD, lines);
    }

    private static void addAttribute(List<Component> lines, Messages messages, Player player,
                                     Attribute attribute, String key) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null || !Double.isFinite(instance.getValue())) return;
        lines.add(messages.get(key, "value", String.format(Locale.ROOT, "%.2f", instance.getValue())));
    }
}
