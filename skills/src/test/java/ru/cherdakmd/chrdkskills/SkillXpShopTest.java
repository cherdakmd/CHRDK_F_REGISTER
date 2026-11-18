package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Покупка кристаллов опыта в меню /skills: Кузня кристаллы не продаёт, платный
 * источник один. Оплата идёт через общий движок PaidActions — durable-намерение
 * BankCharge, идемпотентная выдача с saveData и полный refund при неудаче.
 * Вид услуги skill-xp-potion сохранён: журнал покупок не переименовывается.
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
        assertTrue(shop.contains("addItem(crystal).isEmpty()"),
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
        assertTrue(config.contains("base-chance: 0.02"));
        assertTrue(config.contains("streak-step: 0.002"));
        assertTrue(config.contains("pity-kills: 25"));
        assertTrue(config.contains("shop-enabled: true"));
        assertTrue(config.contains("price: 96"));
        assertTrue(config.contains("cooldown-seconds: 0"));
        String schema = read("src/main/resources/config-schema.yml");
        assertTrue(schema.contains("xp-potions.base-chance"));
        assertTrue(schema.contains("xp-potions.streak-step"));
        assertTrue(schema.contains("xp-potions.pity-kills"));
        assertTrue(schema.contains("xp-potions.shop-enabled"));
        assertTrue(schema.contains("xp-potions.price"));
        assertTrue(schema.contains("xp-potions.cooldown-seconds"));
    }

    @Test
    void stalePotionTextsAreMigratedFromOwnerFiles() throws IOException {
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("%missing%"),
                "остаток до уровня навыка показывается после каждого кристалла");
        assertFalse(messages.contains("%left%"),
                "старый суточный остаток зелий (%left%) не возвращается");
        assertFalse(messages.contains("xp-potion-daily-limit"),
                "суточный лимит зелий удалён вместе с текстом");
        String schema = read("src/main/resources/config-schema.yml");
        assertTrue(schema.contains("messages-version: 11"));
        assertTrue(schema.contains("remove: [messages.xp-potion-daily-limit, messages.xp-potion-used]"),
                "старые значения с %left% вычищаются из файлов владельцев миграцией");
        assertTrue(schema.contains("remove: [messages.menu-tab-potions, messages.xp-potion-unavailable,"),
                "v5 переписывает тексты магазина и вкладки: старые формулировки про зелья вычищены");
    }

    @Test
    void stackPurchaseClickCooldownAndProgressFeedbackArePinned() throws IOException {
        String shop = read("src/main/java/ru/cherdakmd/chrdkskills/SkillXpShop.java");
        assertTrue(shop.contains("boolean buy(Player player, SkillType skill, int count)"),
                "покупка принимает количество: обычный клик — один, Shift — стак");
        assertTrue(shop.contains("xp-potions.shop-stack-size"));
        assertTrue(shop.contains("xp-potions.shop-click-cooldown-ms"),
                "задержка между кликами покупки настраивается");
        assertTrue(shop.contains("Math.max(1, Math.min(stackSize(), count))"),
                "количество ограничено стаком и не может быть нулём");
        assertTrue(shop.contains("addItem(crystal).isEmpty()"),
                "выдача остаётся проверяемой и включает полный refund");
        assertTrue(shop.contains("if (!fits(player.getInventory(), crystal, count)) return false;"),
                "место проверяется до выдачи: частичный стак не выдаётся и не дублирует оплату");
        assertTrue(shop.contains("payments.perform(player, player, price, \"skill-xp-potion\", skill.id()"),
                "оплата стака — одно durable-намерение на полную сумму");
        String gui = read("src/main/java/ru/cherdakmd/chrdkskills/SkillsGui.java");
        assertTrue(gui.contains("onPotionsClick(player, holder, slot, event.isShiftClick())"));
        assertTrue(gui.contains("shop.buy(player, skill, count)"));
        String items = read("src/main/java/ru/cherdakmd/chrdkskills/SkillXpItems.java");
        assertTrue(items.contains("SkillCurve.bar(progress.percent(), 10)"),
                "после кристалла показывается полоса прогресса уровня навыка");
        assertTrue(items.contains("xp-potion-used-max"));
        assertTrue(items.contains("\"missing\", String.valueOf(Math.max(0L, progress.need() - progress.into()))"),
                "сообщение показывает остаток XP до следующего уровня");
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("shop-stack-size: 64"));
        assertTrue(config.contains("shop-click-cooldown-ms: 750"));
        String schema = read("src/main/resources/config-schema.yml");
        assertTrue(schema.contains("config-version: 7"));
        assertTrue(schema.contains("messages-version: 11"));
    }
}
