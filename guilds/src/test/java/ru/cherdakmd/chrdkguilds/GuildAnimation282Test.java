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
            for (String tag : List.of("ЦРАК", "A", "&c<red>", "Я🚀", "abв")) {
                for (String key : List.of("chat-prefix", "peace-chat-prefix", "peace-pending-prefix", "tab-battle-prefix", "tab-peace-prefix", "tab-peace-pending-prefix")) {
                    var styles = new LabelStyleService((id, player, anchor) ->
                            GuildNameTag.animatedPrefix(messages, key,
                                    GuildStyleAnimation.render(tag, "gold", "crown", animation, now.get())) + anchor,
                            prefs, ranks);
                    Set<List<Integer>> chatColors = new HashSet<>();
                    Set<List<Integer>> tabColors = new HashSet<>();
                    Component firstMessage = null;
                    for (long time = 0; time < 12000; time += 250) {
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
                                : key.equals("tab-battle-prefix") ? 0xff5555 : key.equals("peace-pending-prefix") ? 0xffff55 : 0x55ff55;
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

    @Test void fullSlotsHiddenLabelsAndLegacyOuterGradientCannotSuppressActivePaidAnimation() throws Exception {
        Path overrides=folder.resolve("custom.yml");
        Files.writeString(overrides,"guilds:\n  tab-battle-prefix: '<gradient:red:blue>%tag%</gradient>'\n"
                +"  chat-prefix: 'SHOULD_NOT_HIDE_TAG'\n"
                +"  tab-battle-prefix-open: '<gradient:red:blue>[</gradient>'\n");
        var messages=Messages.from(Files.newInputStream(Path.of("src/main/resources/messages.yml")),overrides,Logger.getAnonymousLogger());
        var prefs=new LabelPreferences(folder.resolve("prefs"),Logger.getAnonymousLogger());prefs.load();
        prefs.setSlots("Steve",List.of("classes","achievements","guilds"));
        prefs.set("Steve",LabelPreferences.Label.GUILDS,false);
        var time=new AtomicLong();
        var styles=new LabelStyleService((id,name,anchor)->switch(id) {
            case "guilds", "guilds-chat", "guilds-tab" -> GuildNameTag.animatedPrefix(messages,
                    id.equals("guilds-tab")?"tab-battle-prefix":"chat-prefix",
                    GuildStyleAnimation.render("ЦРАК","gold","crown","aurora",time.get()))+anchor;
            case "classes" -> "<blue>[Класс]</blue> "+anchor;
            case "achievements" -> "<green>[Титул]</green> "+anchor;
            default -> anchor;
        },prefs,new LabelStyleService.RankLookup(){
            public LabelStyleService.Rank view(String name){return null;}
            public List<String> registrationOrder(){return List.of("classes","achievements","guilds");}
        });
        var assignments=styles.assignments("Steve");Set<List<Integer>> visible=new HashSet<>();
        for(long now=0;now<12000;now+=250) {
            time.set(now);
            for(String markup:List.of(styles.decorateForTab("Steve","Steve",null),styles.decorate("Steve","Steve",null,true))) {
                Component component=messages.renderTemplate("%player_tagged%","player_tagged",Messages.trusted(markup));
                String text=PLAIN.serialize(component);
                assertTrue(text.contains("ЦРАК"),text);assertEquals(1,text.split("ЦРАК",-1).length-1);
                assertFalse(text.contains("SHOULD_NOT_HIDE_TAG"));assertTrue(text.startsWith("Steve · [Класс] · [Титул]"));
                int at=text.indexOf("ЦРАК");visible.add(colors(component).subList(at,at+4));
                assertTrue(colors(component).subList(0,5).stream().allMatch(Objects::isNull));
            }
        }
        assertTrue(visible.size()>=24);assertEquals(assignments,styles.assignments("Steve"));
        assertEquals(2,styles.selection("Steve").limit());
        assertFalse(PLAIN.serialize(MINI.deserialize(styles.decorate("Steve","Steve",null))).contains("ЦРАК"));
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

    @Test void transferHasNoWaitEvenAfterFirstAssignmentAndRestart() {
        var store = new GuildStyleStore(folder, Logger.getAnonymousLogger());
        UUID owner = UUID.randomUUID();
        assertEquals(GuildStyleStore.Result.OK, store.select(owner, "Steve", "old", "animation", "shine", 1000));
        assertEquals(GuildStyleStore.Result.OK, store.select(owner, "Steve", "new", "name", "gold", 1001));
        assertTrue(store.forGuild("old").isEmpty());
        assertEquals("shine", store.of(owner).choices().get("animation").preset());
        var restored = new GuildStyleStore(folder, Logger.getAnonymousLogger());
        assertEquals(GuildStyleStore.Result.OK, restored.select(owner, "Steve", "old", "animation", "pulse", 1002));
        assertTrue(restored.forGuild("new").isEmpty());
        assertEquals(2, restored.of(owner).choices().size());
        assertEquals("pulse", restored.of(owner).choices().get("animation").preset());
    }
}
