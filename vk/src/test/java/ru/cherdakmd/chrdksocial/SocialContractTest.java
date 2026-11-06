package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Контракты «один плагин, два мессенджера», проверяемые по исходникам без Bukkit. */
class SocialContractTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void onePluginNotTwoModules() throws Exception {
        String settings = read("../settings.gradle");
        assertFalse(settings.contains("include 'max'"), "MAX живёт внутри модуля vk, отдельного JAR нет");
        assertTrue(settings.contains("include 'vk'"));
        String pluginYml = read("src/main/resources/plugin.yml");
        assertTrue(pluginYml.contains("name: CHRDK_VK"));
        assertTrue(pluginYml.contains("main: ru.cherdakmd.chrdkvk.VkPlugin"));
        assertTrue(pluginYml.contains("chrdksocial.bypass"));
    }

    @Test
    void pluginWiresBothSubsystemsAndSharedCodeRegistry() throws Exception {
        String main = read("src/main/java/ru/cherdakmd/chrdkvk/VkPlugin.java");
        assertTrue(main.contains("new SocialService(this, socialConfig)"));
        assertTrue(main.contains("new MaxService(this, socialService, socialConfig)"));
        assertTrue(main.contains("socialService.attachMax(maxService)"));
        String vkService = read("src/main/java/ru/cherdakmd/chrdkvk/VkService.java");
        assertTrue(vkService.contains("social.handleIncomingCode(code, \"vk\""),
                "код из ВК гасится общим реестром привязок");
    }

    @Test
    void configMessagesAndCommandsCoverSocialFlow() throws Exception {
        String config = read("src/main/resources/config.yml");
        for (String key : new String[]{"social:", "require-link:", "grace-days:",
                "newcomer-grace-days:", "donate-access:", "product-ids:", "permanent:",
                "max:", "api-base-url:", "public-url:", "keystore:", "polling:", "news:"}) {
            assertTrue(config.contains(key), "нет ключа " + key);
        }
        assertTrue(config.contains("https://platform-api2.max.ru/"));
        String messages = read("src/main/resources/messages.yml");
        for (String key : new String[]{"gate-choice", "gate-code", "gate-howto", "linked",
                "gate-donate", "donate-welcome", "gate-grace", "gate-closed", "gate-staff",
                "reminder-choice", "action-bar", "action-bar-closed", "action-nag",
                "title", "subtitle", "command-blocked", "unlinked", "reward",
                "code-cooldown", "disabled"}) {
            assertTrue(messages.contains(key), "нет сообщения social." + key);
        }
        String pluginYml = read("src/main/resources/plugin.yml");
        for (String command : new String[]{"link:", "social:", "max:"}) {
            assertTrue(pluginYml.contains(command), "нет команды " + command);
        }
    }

    @Test
    void maxTokenNeverInUrlAndWebhookChecksSecret() throws Exception {
        String client = read("src/main/java/ru/cherdakmd/chrdksocial/MaxApiClient.java");
        assertTrue(client.contains(".header(\"Authorization\", token)"));
        assertFalse(client.contains("access_token="));
        String server = read("src/main/java/ru/cherdakmd/chrdksocial/MaxWebhookServer.java");
        assertTrue(server.contains("MessageDigest.isEqual"));
        assertTrue(server.contains("X-Max-Bot-Api-Secret-Token".toLowerCase()) ||
                server.contains("x-max-bot-api-secret-token"));
        assertFalse(server.contains("com.sun.net.httpserver"),
                "--release 25 не должен требовать jdk.httpserver");
    }

    @Test
    void frozenLobbyBlocksActionsBeforeLink() throws Exception {
        String listener = read("src/main/java/ru/cherdakmd/chrdksocial/SocialGateListener.java");
        assertTrue(listener.contains("PlayerMoveEvent"));
        assertTrue(listener.contains("PlayerCommandPreprocessEvent"));
        assertTrue(listener.contains("AsyncChatEvent"));
        assertTrue(listener.contains("InventoryClickEvent"));
        String service = read("src/main/java/ru/cherdakmd/chrdksocial/SocialService.java");
        assertTrue(service.contains("SocialService.allowedCommands()") || service.contains("allowedCommands()"));
        assertTrue(service.contains("graceActive(now)"));
        assertTrue(service.contains("staffBypass(player)"));
        assertTrue(service.contains("notifyLinked(playerName"), "снятие заморозки без релога");
    }

    @Test
    void socialUsageMatchesHandlerIncludingDonor() throws Exception {
        String pluginYml = read("src/main/resources/plugin.yml");
        int socialBlock = pluginYml.indexOf("\n  social:\n");
        assertTrue(socialBlock >= 0, "в plugin.yml нет команды /social");
        int usageAt = pluginYml.indexOf("usage:", socialBlock);
        assertTrue(usageAt > socialBlock, "у /social нет usage");
        String pluginUsage = pluginYml.substring(usageAt, pluginYml.indexOf('\n', usageAt));
        String handler = read("src/main/java/ru/cherdakmd/chrdksocial/SocialCommandHandler.java");
        String handlerUsage = handler.lines()
                .filter(line -> line.contains("§e/social ["))
                .findFirst().orElseThrow(() -> new AssertionError("нет подсказки usage в обработчике"));
        assertEquals(section(pluginUsage), section(handlerUsage),
                "plugin.yml и обработчик должны перечислять одни и те же подкоманды");
        assertTrue(section(pluginUsage).contains("donor"), "подкоманда /social donor должна быть в usage");
        assertTrue(handler.contains("return List.of(\"status\", \"unlink\", \"emergency\", \"setchat\", \"chats\", \"donor\", \"testnews\", \"reload\")"));
    }

    /** Содержимое внешних квадратных скобок подсказки, включая вложенные [ник]. */
    private static String section(String line) {
        int open = line.indexOf('[');
        int close = line.lastIndexOf(']');
        assertTrue(open >= 0 && close > open, "битая подсказка: " + line);
        return line.substring(open + 1, close);
    }

    @Test
    void webhookUsesOfficialSecretHeaderAndTransportSwitch() throws Exception {
        String server = read("src/main/java/ru/cherdakmd/chrdksocial/MaxWebhookServer.java");
        assertTrue(server.contains("x-max-bot-api-secret"),
                "MAX шлёт секрет в X-Max-Bot-Api-Secret");
        String service = read("src/main/java/ru/cherdakmd/chrdksocial/MaxService.java");
        assertTrue(service.contains("next.maxTransport()"),
                "max.transport должен реально переключать транспорт, а не только парситься");
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("X-Max-Bot-Api-Secret"));
    }

    @Test
    void playersWithoutSocialNetworksHaveAThirdPath() throws Exception {
        String service = read("src/main/java/ru/cherdakmd/chrdksocial/SocialService.java");
        assertTrue(service.contains("recordFirstSeen(player.getName(), now)"),
                "первый вход фиксируется для личного срока");
        assertTrue(service.contains("graceActive(player, now)"));
        assertTrue(service.contains("api.hasActivePass(playerName)"),
                "доступ читает подтверждённую оплату из ядра");
        assertTrue(service.contains("api.hasEntitlement(playerName, id)"));
        assertTrue(service.contains("social.donate-welcome"));
        assertTrue(service.contains("social.gate-donate"));
        assertFalse(service.contains("sender.sendMessage(\"оплатил\")"),
                "слова игрока не принимаются как подтверждение оплаты");
        String handler = read("src/main/java/ru/cherdakmd/chrdksocial/SocialCommandHandler.java");
        assertTrue(handler.contains("case \"donor\""));
        assertTrue(handler.contains("service.donorGrant("));
        assertTrue(handler.contains("service.donorRevoke("));
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("%url%"), "ссылка на сайт донатов видна в лобби");
        assertTrue(messages.contains("%date%"));
    }

    @Test
    void socialAccessProductIsRealAndDeliveredByExactCommand() throws Exception {
        String core = read("../src/main/resources/config.yml");
        assertTrue(core.contains("- id: social-access"), "товар должен быть в shop.products ядра");
        assertTrue(core.contains("name: \"<aqua>Вход без ВК и MAX</aqua>\""));
        assertTrue(core.contains("price: 150"));
        assertTrue(core.contains("days: 30"));
        assertTrue(core.contains("trademc-export: false"),
                "проходка не добавляет 27-й экспортируемый SKU в комплект TradeMC");
        assertFalse(core.lines().anyMatch(line -> line.contains("id: social-access") && line.contains("rank:")),
                "товар не должен выдавать ранг");

        String vkConfig = read("src/main/resources/config.yml");
        assertTrue(vkConfig.contains("mode: either"),
                "ранг ИЛИ проходка: ранги дороже карточки и должны снимать ограничение");
        assertTrue(vkConfig.contains("product-ids: [social-access, social-access-day]"));

        String card = read("../docs/shop/social-access.md");
        assertTrue(card.contains("pass shopbuy %username% social-access"));
        assertTrue(card.contains("150 ₽"));
        assertTrue(card.contains("30 дней"));
        assertTrue(card.contains("social-access.png"));
    }

    @Test
    void socialAccessDayIsOneDayForFifteenRublesAndUsesBothMechanisms() throws Exception {
        String core = read("../src/main/resources/config.yml");
        assertTrue(core.contains("- id: social-access-day"), "дневной тариф есть в shop.products");
        assertTrue(core.contains("price: 15"));
        assertTrue(core.contains("days: 1"));
        assertTrue(core.contains("group: \"social-day\""));
        assertTrue(core.contains("permissions: [\"chrdksocial.bypass\"]"));
        assertTrue(core.contains("trademc-export: false"),
                "дневной тариф не добавляет 27-ю карточку TradeMC");
        assertTrue(core.contains("lp-social-bypass-permission"),
                "узел обхода настраивается в integrations ядра");

        String vkConfig = read("src/main/resources/config.yml");
        assertTrue(vkConfig.contains("product-ids: [social-access, social-access-day]"),
                "оба тарифа снимают ограничение через entitlement");

        String card = read("../docs/shop/social-access-day.md");
        assertTrue(card.contains("pass shopbuy %username% social-access-day"));
        assertTrue(card.contains("15 ₽"));
        assertTrue(card.contains("1 день"));
        assertTrue(card.contains("chrdksocial.bypass"));
    }

    @Test
    void tabCountersAreWiredEndToEnd() throws Exception {
        String core = read("../src/main/resources/config.yml");
        assertTrue(core.contains("%chrdksocial_access%"));
        assertTrue(core.contains("%chrdksocial_link%"));
        assertTrue(core.contains("%tab_warns%"), "существующие счётчики не потеряны");

        String expansion = read("src/main/java/ru/cherdakmd/chrdksocial/SocialPlaceholders.java");
        assertTrue(expansion.contains("return \"chrdksocial\""));
        assertTrue(expansion.contains("case \"access\""));
        assertTrue(expansion.contains("case \"link\""));
        assertTrue(expansion.contains("SocialAccessPolicy.accessCounter("));
        assertTrue(expansion.contains("SocialAccessPolicy.linkCounter("));

        String main = read("src/main/java/ru/cherdakmd/chrdkvk/VkPlugin.java");
        assertTrue(main.contains("new SocialPlaceholders(this, socialService)"));
        assertTrue(main.contains("registerPlaceholders()"));
        assertTrue(main.contains("isPluginEnabled(\"PlaceholderAPI\")"),
                "без PAPI плагин должен включаться без ошибок");

        String gradle = read("build.gradle");
        assertTrue(gradle.contains("placeholderapi:2.12.3"), "PAPI нужен в compileOnly модуля vk");
        assertTrue(gradle.contains("chrdk.papiJar"), "CI использует тот же pinned jar, что и ядро");

        String service = read("src/main/java/ru/cherdakmd/chrdksocial/SocialService.java");
        assertTrue(service.contains("entitlementUntil(playerName, id)"));
        assertTrue(service.contains("linkProvider(String playerName)"));
    }

    @Test
    void newProductDoesNotRepublishHistoricalTrademcKit() throws Exception {
        String kit = read("../tools/trademc/build.py");
        assertTrue(kit.contains("p.get(\"trademc-export\") is not False"));
        String regression = read("../tools/trademc/test_build.py");
        assertTrue(regression.contains("'social-access'"));
        assertTrue(regression.contains("'social-access-day'"));
        assertTrue(regression.contains("self.assertEqual(26, len(kit.catalog()[1]))"));
        assertTrue(regression.contains("self.assertEqual(32, len(live))"),
                "в живом каталоге два тарифа проходки, но комплект TradeMC прежний");
    }

    @Test
    void everyNewcomerIsNaggedUntilTheyChooseLinkOrPurchase() throws Exception {
        String service = read("src/main/java/ru/cherdakmd/chrdksocial/SocialService.java");
        assertFalse(service.contains("if (isFrozen(player)) {\n                plugin.messages().send(player, \"social.reminder\""),
                "напоминание не должно доставаться только уже замороженным");
        assertTrue(service.contains("\"social.reminder-choice\""));
        assertTrue(service.contains("updateActionBars"));
        assertTrue(service.contains("nagOnAction"));
        assertTrue(service.contains("showEntryTitle"));
        assertTrue(service.contains("config.reminderTitleOnJoin()"));
        assertTrue(service.contains("config.reminderSound()"));
        assertTrue(service.contains("\"social.gate-staff\""),
                "op/bypass больше не выходит из onJoin без единой строки");
        assertTrue(service.contains("\"social.gate-choice\""));
        assertTrue(service.contains("\"social.gate-closed\""));

        String listener = read("src/main/java/ru/cherdakmd/chrdksocial/SocialGateListener.java");
        assertTrue(listener.contains("service.nagOnAction(player)"));
        assertTrue(listener.contains("nagOnAction(event.getPlayer())"));

        String messages = read("src/main/resources/messages.yml");
        for (String key : new String[]{"gate-choice", "gate-code", "gate-howto", "gate-donate",
                "gate-grace", "gate-closed", "gate-staff", "reminder-choice", "action-bar",
                "action-bar-closed", "action-nag", "title", "subtitle"}) {
            assertTrue(messages.contains("\"" + key + "\""), "нет сообщения social." + key);
        }
        assertTrue(messages.contains("150 ₽ / 30 дней"), "оба варианта должны называть цену и срок");
        assertTrue(messages.contains("15 ₽ / 1 день"),
                "дневной тариф за 15 ₽ упоминается в подсказках");
        assertTrue(messages.contains("%vk%"), "инструкция содержит кликабельную ссылку на ВК");
        assertTrue(messages.contains("/link"), "инструкция называет команду получения кода");
        assertFalse(messages.contains("ВК/MAX"),
                "MAX в разработке: игрокам предлагается только ВКонтакте");
        assertTrue(messages.contains("1)"), "инструкция пошаговая");

        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("reminder-seconds: 3600"));
        assertTrue(config.contains("nagging: true"));
        assertTrue(config.contains("action-bar-seconds: 3600"));
        assertTrue(config.contains("action-nag-seconds: 3600"));
    }

    @Test
    void chatRemindersShareOneCooldownAndVkLinkIsPassedToTemplates() throws Exception {
        String service = read("src/main/java/ru/cherdakmd/chrdksocial/SocialService.java");
        assertTrue(service.contains("lastChatReminder"),
                "периодическое напоминание и пинг при действиях делят один кулдаун");
        assertTrue(service.contains("allowChatReminder"));
        assertTrue(service.contains("if (!allowChatReminder(key, now)) return;"));
        assertTrue(service.contains("if (!allowChatReminder(key, now)) continue;"));
        assertFalse(service.contains("lastActionNag"), "старый раздельный кулдаун удалён");
        assertTrue(service.contains("boolean showGate = !config.reminderNagging() || allowChatReminder(key, now);"),
                "блок при входе тоже делит общий часовой кулдаун");
        assertTrue(service.contains("lastFrozenNotice"),
                "короткое «действие доступно после привязки» не спамит при каждом движении");
        assertTrue(service.contains("restartReminderTimers()"),
                "/social reload перезапускает таймеры с новыми интервалами");
        assertTrue(service.contains("\"vk\", config.vkCommunityUrl()"),
                "ссылка на сообщество ВК подставляется во все шаблоны");
        assertTrue(service.contains("\"social.gate-howto\""));
        String handler = read("src/main/java/ru/cherdakmd/chrdksocial/SocialCommandHandler.java");
        assertFalse(handler.contains("\"social.gate-howto\");"),
                "/link не должен отправлять инструкцию с сырыми %code% и %vk%");
        assertTrue(handler.contains("\"vk\", service.config().vkCommunityUrl()"));
    }

    @Test
    void tabShowsExplicitUnlinkedCounterAndPapiLoadsBeforeVk() throws Exception {
        String core = read("../src/main/resources/config.yml");
        assertTrue(core.contains("%chrdksocial_status%"));
        assertTrue(core.contains("%chrdksocial_link%"));
        assertTrue(core.contains("%chrdksocial_access%"));
        String expansion = read("src/main/java/ru/cherdakmd/chrdksocial/SocialPlaceholders.java");
        assertTrue(expansion.contains("case \"status\""));
        assertTrue(expansion.contains("social.tabStatus(name, now)"));
        String policy = read("src/main/java/ru/cherdakmd/chrdksocial/SocialAccessPolicy.java");
        assertTrue(policy.contains("⚠ БЕЗ ПРИВЯЗКИ"));
        assertTrue(policy.contains("⛔ ВХОД ЗАКРЫТ"));
        String pluginYml = read("src/main/resources/plugin.yml");
        assertTrue(pluginYml.contains("softdepend:"));
        assertTrue(pluginYml.contains("PlaceholderAPI"),
                "без softdepend expansion может не зарегистрироваться");
        String main = read("src/main/java/ru/cherdakmd/chrdkvk/VkPlugin.java");
        assertTrue(main.contains("getLogger().severe"));
        assertTrue(main.contains("runTaskLater(this, this::registerPlaceholders"));
    }

    @Test
    void ciPublisherIsWiredIntoRunAndWorkflow() throws Exception {
        String runner = read("../tools/social/run.py");
        assertTrue(runner.contains("from max_publisher import"));
        assertTrue(runner.contains("publish_max("));
        String workflow = read("../.github/workflows/vk-news.yml");
        assertTrue(workflow.contains("MAX_AUTOPUBLISH_ENABLED"));
        assertTrue(workflow.contains("MAX_BOT_TOKEN"));
        assertTrue(workflow.contains("MAX_NEWS_CHAT_ID"));
    }
}
