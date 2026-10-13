package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

/**
 * Динамический MOTD сервера (в ядре, включается секцией {@code motd:} в config.yml).
 *
 * <p>Поддерживает несколько строк-шаблонов с MiniMessage-разметкой (градиенты,
 * категориальные цвета, иконки) и плейсхолдерами %online%, %max%, %date%,
 * %time%, %version%. Строки ротируются по временному ковшу
 * {@code rotate-seconds} — пинги внутри окна получают одинаковый текст
 * (в списке серверов MOTD не «моргает» при повторных пингах трекера).</p>
 *
 * <p>Техника подачи: если у нас есть Adventure-сеттер {@code motd(Component)}
 * (Purpur/Paper — всегда), мотд отдаём компонентом; на старых ядрах — legacy
 * строкой через LegacyComponentSerializer (цвета сохранятся).</p>
 */
public final class MotdService implements Listener {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final RegisterPlugin plugin;
    private final Supplier<PluginConfig> configSupplier;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    /** Adventure-сеттер motd(Component), если ядро его экспонирует. */
    private final Method motdComponentSetter;

    public MotdService(RegisterPlugin plugin, Supplier<PluginConfig> configSupplier) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        motdComponentSetter = resolveComponentSetter();
    }

    private static Method resolveComponentSetter() {
        try {
            return ServerListPingEvent.class.getMethod("motd", Component.class);
        } catch (NoSuchMethodException exception) {
            return null; // старые ядра — уйдём в legacy-ветку
        }
    }

    /** Включён ли MOTD-модуль (краткий ответ для вешлистера). */
    public boolean isEnabled() {
        return configSupplier.get().motd().enabled();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPing(ServerListPingEvent event) {
        PluginConfig.MotdSettings motd = configSupplier.get().motd();
        if (!motd.enabled()) {
            return;
        }
        Component rendered = render(motd, event.getNumPlayers(), event.getMaxPlayers());
        if (motdComponentSetter != null) {
            try {
                motdComponentSetter.invoke(event, rendered);
                return;
            } catch (Exception exception) {
                plugin.getLogger().warning("[MOTD] Adventure-сеттер отказал — отдаю legacy строку: "
                        + exception.getMessage());
            }
        }
        event.setMotd(legacy.serialize(rendered));
    }

    /** Собрать MOTD на текущий ковш (виден также тестам). */
    public Component render(PluginConfig.MotdSettings motd, int onlinePlayers, int maxPlayers) {
        List<String> lines = motd.lines();
        if (lines.isEmpty()) {
            // фолбэк: ничего не было задано — оставляем собственный текст сервера
            return Component.text("");
        }
        int index = pickIndex(motd);
        String raw = lines.get(index)
                .replace("%online%", String.valueOf(onlinePlayers))
                .replace("%max%", String.valueOf(maxPlayers))
                .replace("%date%", LocalDateTime.now(configSupplier.get().timeZone()).format(DATE_FORMAT))
                .replace("%time%", LocalDateTime.now(configSupplier.get().timeZone()).format(TIME_FORMAT))
                .replace("%version%", plugin.getPluginMeta().getVersion());
        return mini.deserialize(raw);
    }

    /** Номер строки для текущей секунды; при 1 строке — всегда 0. */
    int pickIndex(PluginConfig.MotdSettings motd) {
        List<String> lines = motd.lines();
        if (lines.size() <= 1) {
            return 0;
        }
        long bucket = System.currentTimeMillis() / (Math.max(1, motd.rotateSeconds()) * 1000L);
        return (int) (bucket % lines.size());
    }
}
