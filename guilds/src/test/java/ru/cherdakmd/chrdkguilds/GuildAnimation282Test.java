package ru.cherdakmd.chrdkguilds;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.LabelPreferences;
import ru.cherdakmd.chrdkregister.LabelStyleService;
import ru.cherdakmd.chrdkregister.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяем видимые цвета после всех сериализаций, а не различие строк с фазой градиента. */
class GuildAnimation282Test {
    @TempDir Path folder;
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test void animatedTagSurvivesGuildTemplateCoreProjectionAndFinalChatAndTabRender() throws Exception {
        var messages = Messages.from(Files.newInputStream(Path.of("src/main/resources/messages.yml")), null,
                Logger.getAnonymousLogger());
        var prefs = new LabelPreferences(folder, Logger.getAnonymousLogger());
        prefs.load();
        prefs.setSlots("Steve", List.of("guilds"));
        var now = new AtomicLong();
        var ranks = new LabelStyleService.RankLookup() {
            public LabelStyleService.Rank view(String name) { return null; }
            public List<String> registrationOrder() { return List.of("guilds"); }
        };
        for (String animation : GuildStyleDefs.ANIMATIONS) {
            for (String tag : List.of("ЦРАК", "A", "&c<red>")) {
                for (String key : List.of("chat-prefix", "peace-chat-prefix", "tab-battle-prefix", "tab-peace-prefix")) {
                    var styles = new LabelStyleService((id, player, anchor) ->
                            GuildNameTag.prefix(messages, key,
                                    GuildStyleAnimation.render(tag, "gold", "crown", animation, now.get())) + anchor,
                            prefs, ranks);
                    Set<List<Integer>> chatColors = new HashSet<>();
                    Set<List<Integer>> tabColors = new HashSet<>();
                    Component firstMessage = null;
                    for (long time = 0; time < 12000; time += 500) {
                        now.set(time);
                        Component chat = messages.renderTemplate("%player_tagged%", "player_tagged",
                                Messages.trusted(styles.decorate("Steve", "Steve", null)));
                        Component tab = messages.renderTemplate("%player_tagged%", "player_tagged",
                                Messages.trusted(styles.decorateForTab("Steve", "Steve", null)));
                        String plain = PLAIN.serialize(chat);
                        assertEquals(plain, PLAIN.serialize(tab));
                        assertTrue(plain.startsWith("Steve · ["), plain);
                        assertTrue(plain.contains("⚜ " + tag), plain);
                        assertEquals(1, plain.split("Steve", -1).length - 1);
                        int borderColor = key.equals("chat-prefix") ? 0x555555
                                : key.equals("tab-battle-prefix") ? 0xff5555 : 0x55ff55;
                        assertEquals(borderColor, colors(chat).get(plain.indexOf('[')));
                        assertEquals(borderColor, colors(chat).get(plain.lastIndexOf(']')));
                        int at = plain.indexOf(tag);
                        chatColors.add(colors(chat).subList(at, at + tag.length()));
                        tabColors.add(colors(tab).subList(at, at + tag.length()));
                        assertTrue(colors(chat).subList(0, 5).stream().allMatch(Objects::isNull), "Цвет клана не красит ник");
                        if (firstMessage == null) firstMessage = chat;
                    }
                    assertTrue(chatColors.size() >= 8, animation + "/" + tag + "/" + key + ": " + chatColors.size());
                    assertEquals(chatColors, tabColors);
                    // Уже отправленный компонент неизменяем: следующий кадр не переписывает историю.
                    now.set(0);
                    assertEquals(firstMessage, MINI.deserialize(styles.decorate("Steve", "Steve", null)));
                }
            }
        }
    }

    private static List<Integer> colors(Component component) {
        List<Integer> colors = new ArrayList<>();
        colors(component, null, colors);
        return colors;
    }
    private static void colors(Component component, TextColor inherited, List<Integer> result) {
        TextColor color = component.color() == null ? inherited : component.color();
        String own = PLAIN.serialize(component.children(List.of()));
        for (int i = 0; i < own.length(); i++) result.add(color == null ? null : color.value());
        for (Component child : component.children()) colors(child, color, result);
    }

    @Test void refusedTransferKeepsTheOldBindingAndChoiceAndReportsTheExactBoundary() {
        var store = new GuildStyleStore(folder, Logger.getAnonymousLogger());
        UUID owner = UUID.randomUUID();
        assertEquals(GuildStyleStore.Result.OK, store.select(owner, "Steve", "old", "animation", "shine", 1000));
        var before = store.of(owner);
        long until = GuildStyleStore.transferAvailableAt(before);
        assertEquals(86_401_000L, until);
        assertTrue(GuildStyleStore.transferBlocked(before, "new", until - 1));
        assertTrue(GuildStyleStore.transferBlocked(before, "new", 999));
        assertFalse(GuildStyleStore.transferBlocked(before, "old", 1001));
        assertFalse(GuildStyleStore.transferBlocked(null, "new", 1001));
        assertEquals(GuildStyleStore.Result.COOLDOWN, store.select(owner, "Steve", "new", "animation", "pulse", until - 1));
        assertEquals(before, store.of(owner));
        assertTrue(store.forGuild("new").isEmpty());
        var restored = new GuildStyleStore(folder, Logger.getAnonymousLogger());
        assertEquals(before, restored.of(owner));
        assertFalse(GuildStyleStore.transferBlocked(restored.of(owner), "new", until));
        assertEquals(GuildStyleStore.Result.OK, restored.select(owner, "Steve", "new", "animation", "pulse", until));
        assertTrue(restored.forGuild("old").isEmpty());
        assertEquals("pulse", restored.of(owner).choices().get("animation").preset());
    }
}
