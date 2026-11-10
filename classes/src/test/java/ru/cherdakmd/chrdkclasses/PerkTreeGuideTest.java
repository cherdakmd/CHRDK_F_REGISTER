package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkskills.tree.PerkTreeMath;
import ru.cherdakmd.chrdkskills.tree.PerkTreePowderSource;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * «Как прокачивать дерево и где брать пыль»: инструкция должна существовать,
 * называть реальные числа движка и не расходиться с константами. Иначе игрок
 * снова увидит дерево без объяснения, а подсказка соврёт про потолки.
 */
class PerkTreeGuideTest {

    private static Messages messages() {
        return Messages.from(
                new java.io.ByteArrayInputStream(read().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                null, Logger.getLogger("PerkTreeGuideTest"));
    }

    private static String read() {
        try {
            return Files.readString(Path.of("src/main/resources/messages.yml"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void guideExplainsLevelTokensTiersAndTheOnlyPowderSource() {
        Messages messages = messages();
        String guide = messages.template("perktree-guide");
        assertTrue(messages.has("perktree-guide"));
        assertTrue(guide.contains("уровень класса × "
                + ClassPerkTreeBridge.domainLevel(1)), "нужна формула уровня домена");
        assertTrue(guide.contains("Каждые " + PerkTreeMath.LEVELS_PER_TOKEN + " уровней"),
                "нужно объяснение токенов и тир-гейтов");
        assertTrue(guide.contains("+1 токен"), "нужна цена шага уровня");
        assertTrue(guide.contains(PerkTreeRegistry.pilot().tiers() + " тиров"),
                "нужно число тиров");
        assertTrue(guide.contains("+" + PerkTreePowderSource.BASE_POWDER + " за моба"),
                "нужна пыль за подтверждённого моба");
        assertTrue(guide.contains(Integer.toString((int) PveAwardPowderRouter.DAILY_POWDER_CAP)),
                "нужен дневной потолок");
        assertTrue(guide.contains("1500") && guide.contains("2000"),
                "нужны оба потолка запаса дерева");
        assertTrue(guide.contains("confirm ТОКЕН"),
                "нужен двухшаговый сброс без угловых скобок MiniMessage");
        assertTrue(guide.contains(Integer.toString((int) (PerkTreeService.RESET_TTL_MILLIS / 1000))),
                "нужен срок подтверждения сброса");
        assertTrue(guide.contains("естественного моба"), "источник пыли должен быть назван явно");
        assertTrue(guide.contains("нельзя купить"), "нужен явный запрет покупки пыли");
        assertTrue(messages.getList("perktree-guide").size() >= 10,
                "инструкция должна быть подробной");
    }

    @Test
    void menuGuideStatusPagesAndDustOnlyKeysExist() {
        Messages messages = messages();
        for (String key : new String[]{
                "perktree-guide-button", "perktree-status-button",
                "perktree-page-prev", "perktree-page-next", "perktree-title-page",
                "perktree-node-dust-only", "perktree-info-hint", "perktree-status"}) {
            assertTrue(messages.has(key), "нет текста меню/подсказки " + key);
        }
        String hint = messages.template("perktree-info-hint");
        for (String placeholder : new String[]{
                "%base%", "%daily%", "%cap%", "%tier%", "%tiers%", "%next%"}) {
            assertTrue(hint.contains(placeholder), "в info-подсказке нет " + placeholder);
        }
        String status = messages.template("perktree-status");
        for (String placeholder : new String[]{
                "%level%", "%powder%", "%tokens%", "%cap%", "%tier%",
                "%tiers%", "%next%", "%nodes%", "%total%"}) {
            assertTrue(status.contains(placeholder), "в панели прогресса нет " + placeholder);
        }
    }

    @Test
    void twentyNodesFitOnTwoPagesAndPageIsClamped() {
        assertEquals(2, PerkTreeGui.pageCount(20, 15));
        assertEquals(1, PerkTreeGui.pageCount(15, 15));
        assertEquals(1, PerkTreeGui.pageCount(0, 15));
        assertEquals(0, PerkTreeGui.clampPage(-3, 2));
        assertEquals(1, PerkTreeGui.clampPage(9, 2));
        assertEquals(0, PerkTreeGui.clampPage(0, 1));
    }
}
