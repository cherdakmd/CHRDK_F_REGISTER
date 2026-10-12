package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CraftRollKeeperTest {

    private static final TierDef.Snapshot RARE = new TierDef.Snapshot("rare", 5, 10);
    private static final TierDef.Snapshot LEGEND = new TierDef.Snapshot("legendary", 1, 25);

    @Test
    void previewIsStableUntilConsumed() {
        CraftRollKeeper keeper = new CraftRollKeeper();
        UUID player = UUID.randomUUID();
        AtomicInteger rolls = new AtomicInteger();

        // перепбор ингредиентов: десять превью — ровно один ролл
        for (int i = 0; i < 10; i++) {
            assertSame(RARE, keeper.preview(player, "IRON_HELMET", () -> {
                rolls.incrementAndGet();
                return RARE;
            }), "превью №" + i + " не должно перевыполнять ролл");
        }
        assertEquals(1, rolls.get(), "ролл ровно один на пару игрок+результат");

        // крафт завершён — следующее превью делает НОВУЮ попытку
        assertSame(RARE, keeper.consume(player, "IRON_HELMET"));
        assertFalse(keeper.hasPending(player, "IRON_HELMET"));
        assertSame(LEGEND, keeper.preview(player, "IRON_HELMET", () -> {
            rolls.incrementAndGet();
            return LEGEND;
        }));
        assertEquals(2, rolls.get());
    }

    @Test
    void emptyRollAlsoSticksAndRequiresCraftToReroll() {
        CraftRollKeeper keeper = new CraftRollKeeper();
        UUID player = UUID.randomUUID();
        AtomicInteger rolls = new AtomicInteger();

        assertNull(keeper.preview(player, "STONE_SWORD", () -> {
            rolls.incrementAndGet();
            return null;
        }), "неупитанный шанс = обычный предмет");
        assertNull(keeper.preview(player, "STONE_SWORD", () -> RARE),
                "пустой ролл тоже пригвожден — иначе тот же баг с дёрганием слотов");
        assertEquals(1, rolls.get());

        assertNull(keeper.consume(player, "STONE_SWORD"));
        assertSame(RARE, keeper.preview(player, "STONE_SWORD", () -> RARE),
                "после крафта — новая попытка");
    }

    @Test
    void rollsAreScopedPerPlayerAndPerResultType() {
        CraftRollKeeper keeper = new CraftRollKeeper();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertSame(RARE, keeper.preview(first, "IRON_HELMET", () -> RARE));
        assertSame(LEGEND, keeper.preview(second, "IRON_HELMET", () -> LEGEND),
                "другой игрок — своя попытка");
        assertSame(LEGEND, keeper.preview(first, "IRON_CHESTPLATE", () -> LEGEND),
                "другой тип результата — другая попытка");
        assertSame(RARE, keeper.preview(first, "IRON_HELMET", () -> LEGEND),
                "переключение между рецептами не мешает уже отложенный ролл");

        // крафт шлема не трогает сапоги
        keeper.consume(first, "IRON_HELMET");
        assertTrue(keeper.hasPending(first, "IRON_CHESTPLATE"));
        assertSame(LEGEND, keeper.preview(first, "IRON_CHESTPLATE", () -> RARE));
    }

    @Test
    void clearDropsEverything() {
        CraftRollKeeper keeper = new CraftRollKeeper();
        UUID player = UUID.randomUUID();
        keeper.preview(player, "IRON_HELMET", () -> RARE);
        assertTrue(keeper.hasPending(player, "IRON_HELMET"));
        keeper.clear(player);
        assertFalse(keeper.hasPending(player, "IRON_HELMET"));
        assertNull(keeper.consume(player, "IRON_HELMET"), "после выхода игрока — ничего");
    }
}
