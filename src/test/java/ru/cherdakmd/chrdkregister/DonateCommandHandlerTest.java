package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Ссылка на магазин не зависит от провайдеров оплаты и не меняет права/экономику. */
class DonateCommandHandlerTest {
    private static final String URL = "https://chrdk.trademc.org/";
    private Messages messages() {
        return Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger());
    }
    private YamlConfiguration yaml(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                java.util.Objects.requireNonNull(getClass().getResourceAsStream("/" + name)), StandardCharsets.UTF_8));
    }
    private <T extends CommandSender> T sender(Class<T> type, boolean permission, List<Component> sent) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) return permission;
            if (method.getName().equals("sendMessage") && args != null && args.length == 1 && args[0] instanceof Component text) {
                sent.add(text); return null;
            }
            if (method.getName().equals("toString")) return "DonateTestSender";
            throw new AssertionError("Команда-ссылка не должна вызывать " + method);
        }));
    }
    private List<ClickEvent> clicks(Component component) {
        List<ClickEvent> out = new ArrayList<>();
        if (component.clickEvent() != null) out.add(component.clickEvent());
        for (Component child : component.children()) out.addAll(clicks(child));
        return out;
    }
    @Test void playerReceivesExactOpenAndCopyLinks() {
        List<Component> sent = new ArrayList<>();
        var command = new DonateCommandHandler(messages());
        assertTrue(command.onCommand(sender(Player.class, true, sent), null, "donate", new String[0]));
        assertEquals(2, sent.size());
        assertTrue(clicks(sent.getFirst()).contains(ClickEvent.openUrl(URL)));
        assertTrue(clicks(sent.get(1)).contains(ClickEvent.copyToClipboard(URL)));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(sent.getFirst()).contains(URL));
    }
    @Test void consoleReceivesPlainAddressWithoutClickableActions() {
        List<Component> sent = new ArrayList<>();
        new DonateCommandHandler(messages()).onCommand(sender(CommandSender.class, true, sent), null, "donate", new String[0]);
        assertEquals(1, sent.size()); assertTrue(clicks(sent.getFirst()).isEmpty());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(sent.getFirst()).contains(URL));
    }
    @Test void deniedPermissionAndUnexpectedArgumentsDoNotSendLinks() {
        for (boolean permitted : List.of(false, true)) {
            List<Component> sent = new ArrayList<>();
            new DonateCommandHandler(messages()).onCommand(sender(Player.class, permitted, sent), null, "donate",
                    permitted ? new String[]{"<click:run_command:'/op me'>"} : new String[0]);
            assertEquals(1, sent.size()); assertTrue(clicks(sent.getFirst()).isEmpty());
        }
    }
    @Test void reloadReplacesTextsAndNullDoesNotLoseThem() {
        List<Component> sent = new ArrayList<>();
        var handler = new DonateCommandHandler(messages());
        var updated = Messages.from(new java.io.ByteArrayInputStream("""
                messages:
                  donate:
                    link: "<green>Обновлено: <click:open_url:'%url%'>%url%</click></green>"
                    copy: "<gray>Копия %url%</gray>"
                """.getBytes(StandardCharsets.UTF_8)), null, Logger.getAnonymousLogger());
        handler.setMessages(updated); handler.setMessages(null);
        handler.onCommand(sender(Player.class, true, sent), null, "donate", new String[0]);
        assertEquals(2, sent.size()); assertTrue(clicks(sent.getFirst()).contains(ClickEvent.openUrl(URL)));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(sent.getFirst()).startsWith("Обновлено:"));
        assertEquals(List.of(), handler.onTabComplete(null, null, "donate", new String[]{"anything"}));
    }
    @Test void descriptorAndBothMenuEntriesUseThePublicCommand() {
        var descriptor = yaml("plugin.yml");
        assertEquals(DonateCommandHandler.PERMISSION, descriptor.getString("commands.donate.permission"));
        assertTrue(descriptor.getBoolean("permissions.chrdkregister.donate.default"));
        List<Map<?, ?>> entries = yaml("config.yml").getMapList("server-menu.items");
        var donate = entries.stream().filter(entry -> "donate".equals(entry.get("command"))).toList();
        assertEquals(2, donate.size());
        var main = donate.stream().filter(entry -> !entry.containsKey("category")).findFirst().orElseThrow();
        assertEquals(24, ((Number) main.get("slot")).intValue());
        assertEquals(DonateCommandHandler.PERMISSION, main.get("permission"));
        assertTrue(String.valueOf(main.get("lore")).contains("chrdk.trademc.org"));
        assertEquals(1, entries.stream().filter(entry -> Integer.valueOf(24).equals(entry.get("slot"))).count());
    }
    @Test void exactlyOneDonationAnnouncementHasTheCorrectClickTarget() {
        var announcements = ChatConfig.fromBukkitConfig(yaml("config.yml")).autoMessageEntries();
        var donation = announcements.stream().filter(entry -> entry.text().contains("/donate")).toList();
        assertEquals(1, donation.size());
        var targets = clicks(messages().markup(donation.getFirst().text()));
        assertTrue(targets.contains(ClickEvent.runCommand("/donate")));
        assertTrue(targets.stream().allMatch(target -> target.equals(ClickEvent.runCommand("/donate"))),
                "подсказка только запрашивает официальную ссылку, не выполняет покупку");
        assertTrue(donation.getFirst().eligible(id -> true, permission -> true, false, false));
        assertEquals(yaml("config.yml").getList("auto-messages.messages").size(), announcements.size());
    }
    @Test void stock239MigratesOnceWithoutTouchingCustomListsOrSchedule() {
        var live = yaml("presentation-legacy-1.4.239.yml");
        var legacy = yaml("presentation-legacy-1.4.239.yml"); var fresh = yaml("config.yml");
        live.set("auto-messages.interval-seconds", 777);
        var changes = PresentationMigration.replacements(live, List.of(legacy), fresh);
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-1.4.239.yml"));
        assertTrue(changes.containsAll(List.of("server-menu.items", "auto-messages.messages")));
        assertEquals(live.getMapList("server-menu.items").size() + 1, fresh.getMapList("server-menu.items").size());
        for (String key : changes) live.set(key, fresh.get(key));
        assertEquals(777, live.getInt("auto-messages.interval-seconds"));
        assertTrue(PresentationMigration.replacements(live, List.of(legacy), fresh).isEmpty());
        live = yaml("presentation-legacy-1.4.239.yml");
        live.set("server-menu.items", List.of(Map.of("command", "custom", "name", "Custom")));
        live.set("auto-messages.messages", List.of("Custom announcement"));
        var custom = PresentationMigration.replacements(live, List.of(legacy), fresh);
        assertFalse(custom.contains("server-menu.items")); assertFalse(custom.contains("auto-messages.messages"));
    }
    @Test void donationLinkDoesNotBypassPasswordAndOptionalTwoFactorGates() {
        for (var stage : AccessManager.PlayerStage.values()) {
            assertEquals(stage == AccessManager.PlayerStage.AUTHENTICATED,
                    PlayerRestrictionListener.isCommandAllowed(stage, "donate"), stage.name());
        }
    }
}
