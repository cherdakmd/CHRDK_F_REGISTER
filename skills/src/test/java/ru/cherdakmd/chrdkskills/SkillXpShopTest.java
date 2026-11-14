package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Покупка зелий в меню /skills (2.0.6): Кузня зелья больше не продаёт, платный
 * источник один. Оплата идёт через общий движок PaidActions — durable-намерение
 * BankCharge, идемпотентная выдача с saveData и полный refund при неудаче.
 */
class SkillXpShopTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void purchaseUsesPaidActionsWithDurableGive() throws IOException {
        String shop = read("src/main/java/ru/cherdakmd/chrdkskills/SkillXpShop.java");
        assertTrue(shop.contains(
                        "new PaidActions(plugin, plugin::coreApi, plugin::messages, \"skill-xp\")"),
                "оплата — общий движок платных действий с источником skill-xp");
        assertTrue(shop.contains(
                        "payments.perform(player, player, price, \"skill-xp-potion\", skill.id()"),
                "покупка идёт через perform: вид и цель услуги фиксируются в журнале");
        assertTrue(shop.contains("player.saveData();"),
                "выдача сохраняет playerdata до закрытия резерва услуги");
        assertTrue(shop.contains("addItem(potion).isEmpty()"),
                "отсутствие места закрывает выдачу и включает полный refund");
    }

    @Test
    void paymentsAreWiredAcrossLifecycle() throws IOException {
        String plugin = read("src/main/java/ru/cherdakmd/chrdkskills/SkillsPlugin.java");
        assertTrue(plugin.contains("xpShop.payments().tick()"),
                "фоновая сверка незавершённых покупок обязательна");
        assertTrue(plugin.contains("xpShop.payments().flushRefunds()"),
                "выключение доставляет накопленные возвраты в банк");
        assertFalse(plugin.contains("ForgeMaterialSource"),
                "зелья сняты из Кузни: провайдер расходников больше не регистрируется");
        String listener = read("src/main/java/ru/cherdakmd/chrdkskills/SkillListener.java");
        assertTrue(listener.contains("payments().recover(event.getPlayer())"),
                "сверка выполняется после входа и авторизации");
        String commands = read("src/main/java/ru/cherdakmd/chrdkskills/SkillCommandHandler.java");
        assertTrue(commands.contains("payments().admin(sender, args)"),
                "админ-диагностика finance/recover доступна через /skillsadmin");
    }

    @Test
    void dropModelAndShopUseNamedConfigKeys() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("base-chance: 0.0022"));
        assertTrue(config.contains("streak-step: 0.00028"));
        assertTrue(config.contains("pity-kills: 145"));
        assertTrue(config.contains("shop-enabled: true"));
        assertTrue(config.contains("price: 96"));
        String schema = read("src/main/resources/config-schema.yml");
        assertTrue(schema.contains("xp-potions.base-chance"));
        assertTrue(schema.contains("xp-potions.streak-step"));
        assertTrue(schema.contains("xp-potions.pity-kills"));
        assertTrue(schema.contains("xp-potions.shop-enabled"));
        assertTrue(schema.contains("xp-potions.price"));
    }
}
