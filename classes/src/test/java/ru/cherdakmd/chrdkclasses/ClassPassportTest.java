package ru.cherdakmd.chrdkclasses;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Проверка общей арифметики и реальных текстов паспорта без Minecraft-сервера. */
class ClassPassportTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final long NOW = 1_792_000_000_000L;

    private static Messages messages() {
        return Messages.from(ClassPassportTest.class.getResourceAsStream("/messages.yml"), null,
                Logger.getLogger("ClassPassportTest"));
    }

    private static String text(java.util.List<net.kyori.adventure.text.Component> lines) {
        return String.join("\n", lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }

    @Test
    void rawEffectiveAndGainRespectUnchangedCaps() {
        ClassEffectView view = new ClassEffectView(EffectKind.MOVEMENT_SPEED, 0.1, 0.1);
        assertEquals(0.2, view.raw(), 0.000001);
        assertEquals(0.15, view.effective(), 0.000001);
        assertEquals(0.05, view.clipped(), 0.000001);
        assertEquals(0, view.gain(0.03), 0.000001);
        ClassEffectView partial = new ClassEffectView(EffectKind.MOVEMENT_SPEED, 0.1, 0.03);
        assertEquals(0.02, partial.gain(0.05), 0.000001);
        assertThrows(IllegalArgumentException.class, () -> partial.gain(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ClassEffectView(EffectKind.ARMOR, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClassEffectView(EffectKind.ARMOR, 0, Double.POSITIVE_INFINITY));
    }

    @Test
    void healthAndRegenUseActualDifferentPulseConversions() {
        assertEquals("5", ClassMath.amount(EffectKind.MAX_HEALTH, 10));
        assertEquals("1", ClassMath.amount(EffectKind.REGEN, 4));
        assertEquals("0,25", ClassMath.amount(EffectKind.REGEN, 1));
        assertEquals("0,125", ClassMath.amount(EffectKind.REGEN, 0.5));
        assertEquals(4, EffectKind.REGEN.cap(), "исправлено описание, а не сила лечения");
    }

    @Test
    void effectScreenUsesHeartsAndExplainsClipping() {
        String lines = text(ClassPassportText.effect(messages(), new ClassEffectView(EffectKind.MAX_HEALTH, 4, 8)));
        assertTrue(lines.contains("Сырая сумма: 6 ♥"));
        assertTrue(lines.contains("После потолка: 5 ♥"));
        assertTrue(lines.contains("Обрезано потолком: 1 ♥"));
        assertFalse(lines.contains("%value%"));
    }

    @Test
    void unavailableBudgetIsNotPresentedAsZeroOrFirstLevel() {
        Map<LegacyClassSnapshotService.Source, ClassDataRead> reads = new EnumMap<>(LegacyClassSnapshotService.Source.class);
        for (var source : LegacyClassSnapshotService.Source.values()) {
            reads.put(source, ClassDataRead.absent(ClassDataRead.Status.ERROR));
        }
        String lines = text(ClassPassportText.progress(messages(), new LegacyClassSnapshotService.Result(reads, null), 12));
        assertTrue(lines.contains("ошибка чтения, не нулевое значение"));
        assertTrue(lines.contains("Вложено в текущее дерево: 12"));
        assertFalse(lines.contains("Всего заработано: 0"));
        assertFalse(lines.contains("Свободно: 0"));
        assertFalse(lines.contains("Уровень 1"));
    }

    @Test
    void effectBreakdownAndGameplayShareExactlyOneCalculation(@TempDir Path dir) {
        ClassStore store = new ClassStore(dir, null);
        TalentService service = new TalentService(ClassConfig.defaults(), store, null);
        for (PlayerClass role : PlayerClass.values()) {
            store.setClass(PLAYER, role.id(), NOW);
            for (Talent talent : ClassCatalog.talents(role.id())) {
                store.learnTalent(PLAYER, talent.id());
            }
            Map<EffectKind, ClassEffectView> details = service.effectDetails(PLAYER);
            assertEquals(EffectKind.values().length, details.size());
            for (ClassEffectView detail : details.values()) {
                assertEquals(detail.effective(), service.effect(PLAYER, detail.kind()));
                assertTrue(detail.effective() <= detail.kind().cap());
            }
            assertThrows(UnsupportedOperationException.class, details::clear);
        }
    }

    @Test
    void noClassDoesNotShowPassiveBenefits(@TempDir Path dir) {
        TalentService service = new TalentService(ClassConfig.defaults(), new ClassStore(dir, null), null);
        assertTrue(service.effects(PLAYER).isEmpty());
        for (ClassEffectView view : service.effectDetails(PLAYER).values()) {
            assertEquals(0, view.effective());
        }
    }

    @Test
    void allSourceStatusesHaveRussianMessages() {
        Messages messages = messages();
        for (ClassDataRead.Status status : ClassDataRead.Status.values()) {
            assertTrue(messages.has("passport-status-" + status.name().toLowerCase(java.util.Locale.ROOT)));
        }
        for (LegacyClassSnapshotService.Source source : LegacyClassSnapshotService.Source.values()) {
            assertTrue(messages.has("passport-source-" + source.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }
}
