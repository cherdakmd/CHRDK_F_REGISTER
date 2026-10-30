package ru.cherdakmd.chrdkclasses;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Класс» в {@code /profile} (1.4.164).
 *
 * <p>Показывает стиль игры и очки. Раздел виден и тем, кто ещё не выбрал класс:
 * пустая карточка объясняет, что выбор бесплатный и меняется раз в неделю —
 * иначе о механике узнают только по слухам.</p>
 */
final class ClassesProfile implements ChrdkRegisterApi.ProfileProvider {

    private final ClassesPlugin plugin;

    ClassesProfile(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 42;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.service() == null) {
            return null;
        }
        Messages messages = plugin.messages();
        PlayerClass playerClass = plugin.service().classOf(context.playerId());
        List<Component> lines = new ArrayList<>();
        if (playerClass == null) {
            lines.add(messages.get("profile-none"));
            lines.add(messages.get("profile-none-hint"));
        } else {
            lines.add(messages.get("profile-class", "class",
                    Messages.trusted(messages.raw("class." + playerClass.id() + ".name"))));
            lines.addAll(plugin.developmentGui().summary(context.playerId()));
            lines.add(messages.get("passport-profile-hint"));
            long left = plugin.service().changeCooldownLeft(context.playerId(),
                    System.currentTimeMillis());
            if (left > 0) {
                lines.add(messages.get("profile-change", "time", ClassMath.clock(left)));
            }
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.IRON_SWORD, lines);
    }
}
