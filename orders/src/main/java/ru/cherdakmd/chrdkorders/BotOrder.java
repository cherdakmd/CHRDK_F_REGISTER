package ru.cherdakmd.chrdkorders;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Заказ на доске: кто просит, что принести и что за это даёт.
 *
 * <p>Все данные лежат прямо в заказе, а не вычисляются из конфига при каждом
 * чтении: админ может поправить цены или убрать бота, и уже висящий заказ не
 * изменится у игрока на глазах. Материал хранится строкой — {@code orders.json}
 * остаётся читаемым, а неизвестный материал не ломает загрузку.</p>
 *
 * @param id             номер заказа для {@code /orders deliver <номер>}
 * @param botId          ид заказчика
 * @param botName        имя заказчика на момент выдачи
 * @param category       категория заказа
 * @param material       материал (имя {@link Material})
 * @param amount         сколько принести
 * @param rewardDiamonds сколько алмазов заплатит бот
 * @param rewardPassHours сколько часов ранга даст сверху (0 — нет)
 * @param minReputation  требуемая репутация
 * @param reputation     сколько репутации получит сдавший
 * @param createdAt      когда заказ появился на доске
 * @param expiresAt      когда бот уйдёт; 0 — бессрочно
 */
public record BotOrder(String id, String botId, String botName, String category,
                       String material, int amount, int rewardDiamonds, int rewardPassHours,
                       int minReputation, int reputation, long createdAt, long expiresAt) {

    public BotOrder {
        id = id == null ? "" : id.trim();
        botId = botId == null ? "" : botId.trim().toLowerCase(Locale.ROOT);
        botName = botName == null ? botId : botName.trim();
        category = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        material = material == null ? "" : material.trim().toUpperCase(Locale.ROOT);
        amount = Math.clamp(amount, 1, 576);
        rewardDiamonds = Math.clamp(rewardDiamonds, 0, 576);
        rewardPassHours = Math.clamp(rewardPassHours, 0, OrderCategory.MAX_PASS_HOURS);
        minReputation = Math.clamp(minReputation, 0, 100_000);
        reputation = Math.clamp(reputation, 0, 100);
        createdAt = Math.max(0L, createdAt);
        expiresAt = Math.max(0L, expiresAt);
    }

    /** Материал заказа или {@code null}, если в текущей версии игры его нет. */
    public Material materialOrNull() {
        return Materials.resolve(material);
    }

    /** Бот ушёл: заказ больше нельзя сдать. */
    public boolean expired(long now) {
        return expiresAt > 0L && now >= expiresAt;
    }

    /** Сколько миллисекунд осталось жить заказу (0 — истёк или бессрочный). */
    public long millisLeft(long now) {
        return expiresAt <= 0L ? 0L : Math.max(0L, expiresAt - now);
    }

    /** Заказ может принести ранг. */
    public boolean hasPassReward() {
        return rewardPassHours > 0;
    }
}
