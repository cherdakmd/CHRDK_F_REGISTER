package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Контракт API v32 для внешних социальных и защитных интеграций (CHRDK_VK):
 * заморозка входа без смены пароля, пересылка админ-уведомлений даже без Discord-вебхука,
 * метка ВКонтакте в /labels и миграция стокового меню 1.4.261.
 */
class VkCoreBridgeTest {

    private static final Logger LOGGER = Logger.getLogger("VkCoreBridgeTest");

    @TempDir
    Path tempDir;

    @Test
    void apiV32MethodsAreAdditiveAndNeverExposePasswordMutation() throws Exception {
        assertEquals(32, ChrdkRegisterApi.API_VERSION);
        assertTrue(ChrdkRegisterApi.class.getMethod("registerSecurityListener", String.class,
                ChrdkRegisterApi.SecurityListener.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("unregisterSecurityListener", String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("registerNotificationListener", String.class,
                ChrdkRegisterApi.NotificationListener.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("unregisterNotificationListener", String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("registerShopBonusProvider", String.class,
                ChrdkRegisterApi.ShopBonusProvider.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("unregisterShopBonusProvider", String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("isLoginFrozen", String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("setLoginFrozen", String.class, boolean.class, String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("terminateSessions", String.class, String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("activeSessionCount", String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("registerGlobalChatListener", String.class,
                ChrdkRegisterApi.GlobalChatListener.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("unregisterGlobalChatListener", String.class).isDefault());
        assertTrue(ChrdkRegisterApi.class.getMethod("deliverExternalGlobalChat",
                String.class, String.class, String.class, String.class, String.class).isDefault());

        for (var method : ChrdkRegisterApi.class.getMethods()) {
            String lower = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("password"),
                    "API не должен позволять менять или сбрасывать пароль из внешних ботов: " + method.getName());
        }
    }

    @Test
    void loginFreezePersistsAcrossReloadAndRequiresRegisteredAccount() {
        RegistrationStore store = new RegistrationStore(tempDir, LOGGER);
        long now = System.currentTimeMillis();
        UUID id = UUID.randomUUID();
        store.recordPlayerSeen(id, "Steve");
        assertFalse(store.setLoginFrozen("Steve", true, now),
                "незарегистрированный аккаунт нельзя заморозить");

        assertTrue(store.registerPassword("Steve", id, "StrongPass#2026", "127.0.0.1", now));
        store.touchSession("Steve", id, "127.0.0.1", now);
        assertEquals(1, store.sessionCount("Steve"));
        assertFalse(store.isLoginFrozen("Steve"));

        assertTrue(store.setLoginFrozen("steve", true, now));
        assertTrue(store.isLoginFrozen("STEVE"));

        RegistrationStore reloaded = new RegistrationStore(tempDir, LOGGER);
        assertTrue(reloaded.isLoginFrozen("Steve"));
        assertTrue(reloaded.verifyPassword("Steve", "StrongPass#2026"),
                "заморозка входа не портит и не меняет пароль");

        assertTrue(reloaded.setLoginFrozen("Steve", false, now + 5_000L));
        assertFalse(reloaded.isLoginFrozen("Steve"));
    }

    @Test
    void notificationListenerReceivesAllowedEventsEvenWithoutDiscordWebhook() throws Exception {
        YamlConfiguration raw = new YamlConfiguration();
        raw.set("notifications.discord-webhook-url", "");
        raw.set("notifications.events", List.of("bruteforce.alert"));
        PluginConfig parsed = PluginConfig.fromBukkitConfig(raw);
        NotificationService service = new NotificationService(() -> parsed, LOGGER);
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> delivered = new AtomicReference<>();
            service.registerListener("vk", (event, title, description, severity) -> {
                delivered.set(event + "|" + title + "|" + description + "|" + severity);
                latch.countDown();
            });
            service.notify("pass.granted", "Пропуск", "Не в списке", "good");
            service.notify("bruteforce.alert", "Перебор паролей", "Игрок: Steve", "warn");
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("bruteforce.alert|Перебор паролей|Игрок: Steve|warn", delivered.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void vkLabelHasAliasesAndMenuSlotAndStock261MigratesOnce() throws Exception {
        assertEquals(LabelPreferences.Label.VK, LabelsCommandHandler.singleOf("vk"));
        assertEquals(LabelPreferences.Label.VK, LabelsCommandHandler.singleOf("вк"));
        assertEquals(LabelPreferences.Label.VK, LabelsCommandHandler.singleOf("вконтакте"));
        assertTrue(LabelsMenuService.slotOf(LabelPreferences.Label.VK) >= 0);

        YamlConfiguration legacy;
        YamlConfiguration fresh;
        try (var inLegacy = getClass().getResourceAsStream("/presentation-legacy-1.4.261.yml");
             var inFresh = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(inLegacy);
            assertNotNull(inFresh);
            legacy = YamlConfiguration.loadConfiguration(new InputStreamReader(inLegacy, StandardCharsets.UTF_8));
            fresh = YamlConfiguration.loadConfiguration(new InputStreamReader(inFresh, StandardCharsets.UTF_8));
        }
        YamlConfiguration live = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(legacy.saveToString()));
        var changes = PresentationMigration.replacements(live, List.of(legacy), fresh);
        assertTrue(changes.contains("server-menu.items"));
        for (String key : changes) {
            live.set(key, fresh.get(key));
        }
        assertTrue(PresentationMigration.replacements(live, List.of(legacy), fresh).isEmpty());
    }

    @Test
    void externalGlobalChatRespectsMutesFreezeWordFilterAndDuplicates() {
        RegistrationStore store = new RegistrationStore(tempDir, LOGGER);
        long now = 1_700_000_000_000L;
        UUID id = UUID.randomUUID();
        store.registerPassword("Steve", id, "StrongPass#2026", "127.0.0.1", now);

        YamlConfiguration rawChat = new YamlConfiguration();
        rawChat.set("chat.enabled", true);
        rawChat.set("chat.anti-caps", true);
        rawChat.set("chat.caps-percent", 70);
        rawChat.set("chat.block-duplicates", true);
        rawChat.set("chat.duplicate-window-seconds", 30);
        rawChat.set("chat.cooldown-ms", 0);
        rawChat.set("chat.filter.enabled", true);
        rawChat.set("chat.filter.mode", "replace");
        rawChat.set("chat.filter.words", List.of("badword"));
        ChatConfig chatCfg = ChatConfig.fromBukkitConfig(rawChat);
        ChatService chat = new ChatService(null, null, chatCfg, null, LOGGER);

        assertEquals("привет ******* всем",
                chat.moderateExternalMessage(store, "vk", "Steve", "Steve", "ПРИВЕТ badword ВСЕМ", now));
        assertNull(chat.moderateExternalMessage(store, "vk", "Steve", "Steve", "привет badword всем", now + 1_000L),
                "дубликат от того же отправителя блокируется");

        // Замороженный или замученный аккаунт не может писать в игру через беседу ВК.
        store.setLoginFrozen("Steve", true, now + 2_000L);
        assertNull(chat.moderateExternalMessage(store, "vk", "Steve", "Steve", "Обход заморозки", now + 3_000L));
        store.setLoginFrozen("Steve", false, now + 4_000L);

        store.setMute("Steve", now + 60_000L, "флуд", "admin");
        assertNull(chat.moderateExternalMessage(store, "vk", "Steve", "Steve", "Обход мута", now + 6_000L));
    }
}
