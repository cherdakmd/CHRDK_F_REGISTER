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
                "max:", "api-base-url:", "public-url:", "keystore:", "polling:", "news:"}) {
            assertTrue(config.contains(key), "нет ключа " + key);
        }
        assertTrue(config.contains("https://platform-api2.max.ru/"));
        String messages = read("src/main/resources/messages.yml");
        for (String key : new String[]{"gate-header", "gate-code", "gate-howto", "linked",
                "reminder", "command-blocked", "unlinked", "reward", "code-cooldown", "disabled"}) {
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
