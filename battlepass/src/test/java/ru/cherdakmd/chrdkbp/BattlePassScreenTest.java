package ru.cherdakmd.chrdkbp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Строки {@code /battlepass}: разметка из config/messages.yml должна рендериться,
 * а не показываться тегами {@code <gray>…</gray>} (баг 1.4.108).
 */
class BattlePassScreenTest {

    private static final Logger LOGGER = Logger.getLogger("BattlePassScreenTest");

    private static final BattlePassConfig.MissionDef ORE = new BattlePassConfig.MissionDef(
            "mine_diamonds", "<gray>Добыть алмазную руду</gray>",
            BattlePassConfig.MissionType.MINE, "diamond_ore", 16, 1, 2);

    private static BattlePassConfig.MissionDef vipOnly() {
        return new BattlePassConfig.MissionDef("hunt_zombies", "<gray>Победить зомби</gray>",
                BattlePassConfig.MissionType.KILL, "zombie", 40, 0, 1);
    }

    private static BattlePassScreen screen() throws Exception {
        try (InputStream stream = Files.newInputStream(resource())) {
            return new BattlePassScreen(Messages.from(stream, null, LOGGER));
        }
    }

    /** Тесты могут запускаться и из корня репозитория, и из папки модуля. */
    private static Path resource() {
        for (String candidate : List.of("src/main/resources/messages.yml",
                "battlepass/src/main/resources/messages.yml")) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("messages.yml не найден ни в одном из путей");
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void progressLineRendersMarkupInsteadOfTags() throws Exception {
        String line = plain(screen().mission("bp",
                new BattlePassView.Mission(ORE, 8, false, false, false)));
        assertTrue(line.contains("Добыть алмазную руду"), "нет описания задания: " + line);
        assertFalse(line.contains("<gray>"), "MiniMessage-тег попал в текст: " + line);
        assertFalse(line.contains("</gray>"), "закрывающий тег попал в текст: " + line);
        assertTrue(line.contains("8/16"), "нет прогресса: " + line);
        assertTrue(line.contains("+1 ч."), "нет бесплатной награды: " + line);
        assertTrue(line.contains("VIP +2 ч."), "нет VIP-награды: " + line);
        assertFalse(line.contains("забрать"), "кнопка не нужна, пока цель не достигнута: " + line);
        assertEquals(8, line.chars().filter(c -> c == '▰' || c == '▱').count(),
                "полоса должна состоять из 8 секций: " + line);
    }

    @Test
    void readyLineOffersClaim() throws Exception {
        String line = plain(screen().mission("bp",
                new BattlePassView.Mission(ORE, 16, false, false, false)));
        assertTrue(line.contains("16/16"), line);
        assertTrue(line.contains("забрать"), "нет кнопки получения награды: " + line);
        assertFalse(line.contains("<"), "в тексте остались теги: " + line);
    }

    @Test
    void vipOnlyMissionExplainsThePass() throws Exception {
        String locked = plain(screen().mission("bp",
                new BattlePassView.Mission(vipOnly(), 40, false, false, false)));
        assertTrue(locked.contains("нужен ранг"), "нет подсказки про ранг: " + locked);
        assertFalse(locked.contains("<light_purple>"), locked);

        String ready = plain(screen().mission("bp",
                new BattlePassView.Mission(vipOnly(), 40, false, false, true)));
        assertTrue(ready.contains("забрать"), "с рангом награда должна быть доступна: " + ready);
    }

    @Test
    void claimedLineMarksMissionDone() throws Exception {
        String line = plain(screen().mission("bp",
                new BattlePassView.Mission(ORE, 16, true, true, true)));
        assertTrue(line.contains("забрано"), "нет отметки о получении: " + line);
        assertFalse(line.contains("забрать"), "кнопка не нужна после получения: " + line);
        assertFalse(line.contains("<"), "в тексте остались теги: " + line);
    }

    @Test
    void missionDoneLineHasNoRawTags() throws Exception {
        String line = plain(screen().missionDone("battlepass", ORE));
        assertTrue(line.contains("Задание выполнено"), line);
        assertTrue(line.contains("Добыть алмазную руду"), line);
        assertTrue(line.contains("забрать"), line);
        assertFalse(line.contains("<"), "в тексте остались теги: " + line);
    }

    private static BattlePassConfig.MissionDef premium() {
        return new BattlePassConfig.MissionDef("prem", "<gray>Добыть камень</gray>",
                BattlePassConfig.MissionType.MINE, "stone", 10, 1, 0,
                BattlePassConfig.DEFAULT_POINTS, List.of(), "", "", 3,
                List.of("trail-flame", "nick-color"));
    }

    @Test
    void premiumRewardIsShownWithProductsAndHours() throws Exception {
        String line = plain(screen().mission("bp",
                new BattlePassView.Mission(premium(), 10, false, false, false, false, true)));
        assertTrue(line.contains("+1 ч."), "бесплатная награда потерялась: " + line);
        assertTrue(line.contains("★ +3 ч."), "нет часов премиум-трека: " + line);
        assertTrue(line.contains("★ подарок"), "нет отметки о товаре: " + line);
        assertTrue(line.contains("забрать"), "премиум-награда должна быть доступна: " + line);
        assertFalse(line.contains("<"), "в тексте остались теги: " + line);
    }

    @Test
    void premiumLockedLineSendsToShop() throws Exception {
        String line = plain(screen().mission("bp",
                new BattlePassView.Mission(premium(), 10, true, false, false, false, false)));
        assertTrue(line.contains("нужен боевой пропуск"),
                "нет подсказки про боевой пропуск: " + line);
        assertFalse(line.contains("забрать"), "без пропуска кнопки быть не должно: " + line);
        assertFalse(line.contains("<"), "в тексте остались теги: " + line);
    }
    @Test
    void guiTitleAndTrackStatusRenderWithoutMarkupOrComponentDebugText() throws Exception {
        try (InputStream stream = Files.newInputStream(resource())) {
            Messages messages = Messages.from(stream, null, LOGGER);
            String title = plain(messages.get("menu-title", "season",
                    BattlePassScreen.described("<gold>Стужа</gold>"), "page", 2, "pages", 3));
            assertTrue(title.contains("Стужа"), title);
            assertTrue(title.contains("2/3"), title);
            assertFalse(title.contains("<"), title);
            List<Component> lore = messages.getList("menu-premium-lore",
                    "vip", messages.mini("menu-track-active"),
                    "premium", messages.mini("menu-track-locked"));
            String text = lore.stream().map(BattlePassScreenTest::plain)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("открыта"), text);
            assertTrue(text.contains("закрыта"), text);
            assertFalse(text.contains("<"), text);
            assertFalse(text.contains("%"), text);
            assertFalse(text.contains("TextComponent"), text);
        }
    }

}
