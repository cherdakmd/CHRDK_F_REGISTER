package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Расписание мировых событий (1.4.160): планирование, порог онлайна, паузы и
 * восстановление после перезапуска.
 */
class EventServiceTest {

    private static final long NOW = 1_000_000L;

    private static WorldEvent event(String id, EventKind kind, int duration, int weight,
                                    int cooldown, int percent) {
        return new WorldEvent(id, id, "", kind, duration, weight, cooldown, percent,
                List.of(Material.IRON_ORE), List.of(Material.BONE), List.of(),
                Material.CLOCK);
    }

    private static EventConfig config(WorldEvent... events) {
        YamlConfigBuilder builder = new YamlConfigBuilder();
        return builder.build(600, 600, List.of(events));
    }

    /** Маленький помощник: собирает EventConfig через YAML — как в бою. */
    private static final class YamlConfigBuilder {

        EventConfig build(int minDelay, int maxDelay, List<WorldEvent> events) {
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    new org.bukkit.configuration.file.YamlConfiguration();
            yaml.set("general.min-players", 2);
            yaml.set("schedule.min-delay-seconds", minDelay);
            yaml.set("schedule.max-delay-seconds", maxDelay);
            int index = 0;
            for (WorldEvent event : events) {
                String path = "events.e" + index++;
                yaml.set(path + ".type", event.kind().name());
                yaml.set(path + ".duration-seconds", event.durationSeconds());
                yaml.set(path + ".weight", event.weight());
                yaml.set(path + ".cooldown-seconds", event.cooldownSeconds());
                yaml.set(path + ".percent", event.percent());
                yaml.set(path + ".blocks", event.blocks().stream().map(Enum::name).toList());
                yaml.set(path + ".items", event.items().stream().map(Enum::name).toList());
            }
            return EventConfig.parse(yaml, null);
        }
    }

    /**
     * Конфиг с одним событием: id в конфиге — {@code e0}, поэтому и проверяем
     * {@code e0}. Помощник выше это учитывает.
     */
    @Test
    void planNextUsesConfiguredDelay() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 300, 10, 0, 50)),
                new Random(1));
        service.planNext(NOW);
        assertEquals(NOW + 600_000L, service.nextAt());
        assertEquals(600L, service.secondsToNext(NOW));
        assertFalse(service.active());
    }

    @Test
    void startWaitsUntilScheduledMoment() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 300, 10, 0, 50)),
                new Random(1));
        service.planNext(NOW);
        assertNull(service.tryStart(NOW + 1_000L, 5), "рано");
        WorldEvent started = service.tryStart(NOW + 600_000L, 5);
        assertNotNull(started);
        assertTrue(service.active());
        assertEquals(NOW + 600_000L + 300_000L, service.endsAt());
        assertEquals(300L, service.secondsLeft(NOW + 600_000L));
    }

    @Test
    void tooFewPlayersPostponesStart() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 300, 10, 0, 50)),
                new Random(1));
        service.planNext(NOW);
        long due = NOW + 600_000L;
        assertNull(service.tryStart(due, 1), "порог — два игрока");
        assertEquals(due + EventService.RETRY_MILLIS, service.nextAt());
        assertNotNull(service.tryStart(due + EventService.RETRY_MILLIS, 2));
    }

    @Test
    void endingEventStartsCooldownAndReplans() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 60, 10, 900, 50)),
                new Random(1));
        service.planNext(NOW);
        long due = NOW + 600_000L;
        assertNotNull(service.tryStart(due, 5));
        assertNull(service.tick(due + 59_000L), "ещё идёт");
        WorldEvent ended = service.tick(due + 60_000L);
        assertNotNull(ended);
        assertFalse(service.active());
        assertTrue(service.coolingDown("e0", due + 60_000L));
        assertFalse(service.coolingDown("e0", due + 60_000L + 900_000L));
        assertTrue(service.nextAt() > due + 60_000L, "новое расписание назначено");
    }

    @Test
    void stopAlsoClosesEvent() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 600, 10, 0, 50)),
                new Random(1));
        service.planNext(NOW);
        assertNotNull(service.tryStart(NOW + 600_000L, 5));
        WorldEvent stopped = service.stop(NOW + 700_000L);
        assertNotNull(stopped);
        assertFalse(service.active());
        assertNull(service.stop(NOW + 700_000L), "второй раз останавливать нечего");
    }

    @Test
    void disabledConfigNeverStarts() {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("general.enabled", false);
        yaml.set("schedule.min-delay-seconds", 60);
        yaml.set("schedule.max-delay-seconds", 60);
        yaml.set("events.e0.type", "XP");
        yaml.set("events.e0.percent", 50);
        yaml.set("events.e0.weight", 10);
        EventService service = new EventService(EventConfig.parse(yaml, null), new Random(1));
        service.planNext(NOW);
        assertNull(service.tryStart(NOW + 600_000L, 100));
        assertNull(service.start("e0", NOW));
    }

    @Test
    void pickSkipsCoolingDownEvents() {
        WorldEvent first = event("e0", EventKind.XP, 300, 10, 3_600, 50);
        WorldEvent second = event("e1", EventKind.XP, 300, 10, 3_600, 50);
        EventService service = new EventService(config(first, second), new Random(7));
        service.planNext(NOW);
        long due = NOW + 600_000L;
        assertNotNull(service.tryStart(due, 5));
        String startedId = service.current().id();
        String otherId = startedId.equals("e0") ? "e1" : "e0";
        service.stop(due + 1_000L);
        assertTrue(service.coolingDown(startedId, due + 2_000L));

        // Пока первое на паузе — второе обязано запускаться.
        service.planNext(due + 2_000L);
        WorldEvent next = service.tryStart(due + 2_000L + 600_000L, 5);
        assertNotNull(next);
        assertEquals(otherId, next.id());
    }

    @Test
    void pickReturnsNullWhenEverythingIsCooling() {
        WorldEvent only = event("e0", EventKind.XP, 60, 10, 3_600, 50);
        EventService service = new EventService(config(only), new Random(3));
        service.planNext(NOW);
        long due = NOW + 600_000L;
        assertNotNull(service.tryStart(due, 5));
        service.stop(due + 1_000L);
        assertNull(EventPicker.pick(List.of(only), service.cooldowns(), due + 2_000L,
                new Random(1)));
        // Расписание сдвигаем в прошлое: иначе tryStart откажется по времени и не
        // дойдёт до выбора события.
        long retryAt = due + 2_000L + 600_000L;
        service.planNext(due + 2_000L);
        assertNull(service.tryStart(retryAt, 5));
        assertEquals(retryAt + EventService.RETRY_MILLIS, service.nextAt());
    }

    @Test
    void restoreKeepsFutureScheduleAndDropsExpiredCooldowns() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 300, 10, 60, 50)),
                new Random(1));
        long future = NOW + 120_000L;
        service.restoreSchedule(future, Map.of("e0", NOW + 60_000L, "old", NOW - 60_000L), NOW);
        assertEquals(future, service.nextAt());
        assertTrue(service.coolingDown("e0", NOW));
        assertFalse(service.coolingDown("old", NOW), "истёкшая пауза отброшена");
    }

    @Test
    void restorePlansWhenSavedMomentPassed() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 300, 10, 0, 50)),
                new Random(1));
        service.restoreSchedule(NOW - 5_000L, Map.of(), NOW);
        assertTrue(service.nextAt() > NOW);
        assertEquals(600L, service.secondsToNext(NOW));
    }

    @Test
    void adminStartByIdIgnoresSchedule() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 300, 10, 0, 50)),
                new Random(1));
        service.planNext(NOW);
        assertNull(service.start("нет-такого", NOW));
        WorldEvent started = service.start("e0", NOW);
        assertNotNull(started);
        assertEquals("e0", started.id());
        assertNull(service.start("e0", NOW), "второе событие поверх первого не начинается");
    }

    @Test
    void supportsFollowCurrentEvent() {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("general.min-players", 1);
        yaml.set("schedule.min-delay-seconds", 60);
        yaml.set("schedule.max-delay-seconds", 60);
        yaml.set("events.star.type", "MINING");
        yaml.set("events.star.weight", 10);
        yaml.set("events.star.percent", 40);
        yaml.set("events.star.duration-seconds", 300);
        yaml.set("events.star.blocks", List.of("IRON_ORE"));
        yaml.set("events.star.meteors.interval-seconds", 20);
        yaml.set("events.star.meteors.blocks", List.of("AMETHYST_BLOCK"));
        yaml.set("events.star.meteors.loot", List.of("AMETHYST_SHARD"));
        EventService service = new EventService(EventConfig.parse(yaml, null), new Random(1));
        assertFalse(service.fallingStars(), "без события звёзд нет");
        assertFalse(service.hordeActive());
        service.planNext(NOW);
        WorldEvent started = service.tryStart(NOW + 60_000L, 1);
        assertNotNull(started);
        assertTrue(service.fallingStars());
        assertEquals(2, service.meteors().maxActive(), "умолчание секции meteors");
        assertFalse(service.hordeActive(), "секции horde нет — нашествия нет");
        service.stop(NOW + 61_000L);
        assertFalse(service.fallingStars(), "после события звёзды гаснут");
        assertSame(MeteorSpawn.NONE, service.meteors());
    }

    @Test
    void bonusGettersFollowCurrentKind() {
        EventService service = new EventService(config(event("e0", EventKind.XP, 300, 10, 0, 42)),
                new Random(1));
        assertEquals(0, service.xpPercent(), "без события бонуса нет");
        service.planNext(NOW);
        assertNotNull(service.tryStart(NOW + 600_000L, 5));
        assertEquals(42, service.xpPercent());
        assertEquals(0, service.miningPercent());
        assertEquals(0, service.lootPercent());
        assertEquals(0, service.effects().size());
        assertSame(EventKind.XP, service.current().kind());
    }
}
