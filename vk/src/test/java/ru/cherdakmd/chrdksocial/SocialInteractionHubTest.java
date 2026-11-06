package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Один роутер на VK и MAX: подтверждения, тикеты, опросы, кулдауны, аудит. */
class SocialInteractionHubTest {

    private static final class Executor implements SocialInteractionHub.ActionExecutor {
        final List<String> calls = new ArrayList<>();

        @Override
        public String run(String provider, String kind, String arg, long userId, long now) {
            calls.add(provider + ":" + kind + ":" + arg);
            return "DONE:" + kind;
        }

        @Override
        public String playerName(String provider, long userId) {
            return "Steve";
        }
    }

    private static SocialInteractionConfig cfg(boolean enabled, boolean callbacks,
                                               boolean dialogs, boolean polls,
                                               boolean tickets, int cooldown) {
        return new SocialInteractionConfig(enabled, callbacks, dialogs, polls, tickets,
                false, false, 0L, cooldown, 120, 300, 220, "Вопрос?");
    }

    @Test
    void disabledHubIgnoresEveryPayload() {
        var hub = new SocialInteractionHub(SocialInteractionConfig.DEFAULT, new Executor(), null);
        assertFalse(hub.handle("vk", 1L, "{\"act\":\"help\"}", 1_000L).hasContent());
    }

    @Test
    void helpReturnsSharedCallbackMenu() {
        Executor executor = new Executor();
        var hub = new SocialInteractionHub(cfg(true, true, true, true, true, 0), executor, null);
        var reply = hub.handle("vk", 1L, "{\"cmd\":\"start\"}", 1_000L);
        assertTrue(reply.text().contains("Интерактивное меню"));
        assertFalse(reply.buttons().isEmpty());
        assertTrue(reply.buttons().stream().anyMatch(b -> b.kind().equals("request")));
        assertTrue(reply.buttons().stream().anyMatch(b -> b.kind().equals("poll_new")));
        assertTrue(executor.calls.isEmpty(), "меню само по себе ничего не выполняет");
    }

    @Test
    void dangerousActionRequiresFreshConfirmation() {
        Executor executor = new Executor();
        var hub = new SocialInteractionHub(cfg(true, true, true, false, false, 0), executor, null);
        long now = 1_000L;
        var blind = hub.handle("vk", 1L, "{\"act\":\"confirm\",\"arg\":\"freeze\"}", now);
        assertTrue(blind.text().contains("не найдено"));
        assertTrue(executor.calls.isEmpty(), "без диалога действие не выполняется");

        var asked = hub.handle("vk", 1L, "{\"act\":\"request\",\"arg\":\"freeze\"}", now);
        assertTrue(asked.text().contains("Подтвердите"));
        assertEquals(2, asked.buttons().size());

        var done = hub.handle("vk", 1L, "{\"act\":\"confirm\",\"arg\":\"freeze\"}", now + 1L);
        assertEquals("DONE:freeze", done.text());
        assertEquals(List.of("vk:freeze:"), executor.calls);
        assertTrue(hub.handle("vk", 1L, "{\"act\":\"confirm\",\"arg\":\"freeze\"}", now + 2L)
                .text().contains("не найдено"), "подтверждение одноразовое");
    }

    @Test
    void expiredDialogCannotBeConfirmed() {
        Executor executor = new Executor();
        var hub = new SocialInteractionHub(cfg(true, true, true, false, false, 0), executor, null);
        hub.handle("vk", 1L, "{\"act\":\"request\",\"arg\":\"logout\"}", 1_000L);
        var late = hub.handle("vk", 1L, "{\"act\":\"confirm\",\"arg\":\"logout\"}", 200_000L);
        assertTrue(late.text().contains("устарело"));
        assertTrue(executor.calls.isEmpty());
    }

    @Test
    void cancelClearsPendingState() {
        Executor executor = new Executor();
        var hub = new SocialInteractionHub(cfg(true, true, true, false, false, 0), executor, null);
        hub.handle("vk", 1L, "{\"act\":\"request\",\"arg\":\"unlink\"}", 1_000L);
        assertTrue(hub.handle("vk", 1L, "{\"act\":\"cancel\"}", 1_001L)
                .snackbar().contains("Отменено"));
        assertNull(hub.dialogs().peek("vk:1", 1_002L));
    }

    @Test
    void ticketIsCreatedOnceAndAudited() {
        Executor executor = new Executor();
        List<SocialInteractionHub.AuditRow> rows = new ArrayList<>();
        var hub = new SocialInteractionHub(cfg(true, true, true, false, true, 30), executor, rows::add);
        var first = hub.handle("vk", 1L, "{\"act\":\"ticket\"}", 1_000L);
        assertEquals("DONE:ticket", first.text());
        assertEquals(1, rows.size());
        assertEquals("social.interaction.ticket", rows.get(0).event());
        assertEquals("Steve", rows.get(0).player());

        var second = hub.handle("vk", 1L, "{\"act\":\"ticket\"}", 2_000L);
        assertTrue(second.snackbar().contains("Слишком часто"),
                "общий кулдаун гасит повторные заявки");
        assertFalse(second.text().contains("DONE:ticket"));
    }

    @Test
    void pollVoteEditsTheSameCard() {
        var hub = new SocialInteractionHub(cfg(true, true, false, true, false, 0),
                new Executor(), null);
        var created = hub.handle("vk", 1L, "{\"act\":\"poll_new\"}", 1_000L);
        assertEquals(2, created.buttons().size());
        String payload = created.buttons().get(0).payload();
        var voted = hub.handle("vk", 1L, payload, 1_001L);
        assertTrue(voted.edit(), "карточка опроса редактируется, а не плодит сообщения");
        assertTrue(voted.text().contains("✅ 1"), voted.text());
        assertTrue(voted.snackbar().contains("Голос учтён"));
        assertFalse(hub.handle("vk", 1L, payload, 1_002L)
                .snackbar().contains("Голос учтён"), "повторный голос не считается");
    }

    @Test
    void sameRouterServesMaxProvider() {
        Executor executor = new Executor();
        var hub = new SocialInteractionHub(cfg(true, true, true, false, false, 0), executor, null);
        hub.handle("max", 7L, "{\"act\":\"request\",\"arg\":\"logout\"}", 1_000L);
        hub.handle("max", 7L, "{\"act\":\"confirm\",\"arg\":\"logout\"}", 1_001L);
        assertEquals(List.of("max:logout:"), executor.calls,
                "MAX идёт через тот же хаб, provider доходит до исполнителя");
        assertEquals(0, hub.dialogs().size(), "диалог закрыт после подтверждения");
    }

    @Test
    void cooldownBlocksBurstsOfSameAction() {
        var hub = new SocialInteractionHub(cfg(true, true, false, false, false, 30),
                new Executor(), null);
        assertTrue(hub.handle("vk", 1L, "{\"act\":\"help\"}", 1_000L).hasContent());
        var blocked = hub.handle("vk", 1L, "{\"act\":\"help\"}", 2_000L);
        assertTrue(blocked.snackbar().contains("Слишком часто"));
        assertTrue(blocked.text().isEmpty());
    }
}
