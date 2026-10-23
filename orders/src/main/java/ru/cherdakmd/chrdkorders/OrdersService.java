package ru.cherdakmd.chrdkorders;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Доска заказов: выдача, сдача, награды и репутация (1.4.154).
 *
 * <p>Заказы приходят «сами»: пока на доске есть место, генератор добавляет
 * новый. Заказчик — бот из конфига, а не сущность и не игрок, поэтому доска
 * работает на одиноком сервере и не зависит от чужих цен. Обмен честный и
 * одномоментный: предметы уходят из инвентаря, алмазы приходят сразу — escrow
 * не нужен.</p>
 *
 * <p>Сдаются только «простые» предметы: если стак не складывается с обычным
 * (имя, чары, прочие компоненты, урон) — он не считается. Так дорогая находка
 * не уйдёт случайно в счёт заказа на уголь.</p>
 */
public final class OrdersService {

    /** Как часто доска добирает заказы вместо истёкших. */
    private static final int BOARD_REFRESH_SECONDS = 60;
    /** Сколько алмазов класть в один стак при выдаче. */
    private static final int DIAMOND_STACK = 64;
    /** Порядок заказов на доске — по номеру, а не по времени записи. */
    private static final Comparator<BotOrder> BY_ID =
            Comparator.comparingLong((BotOrder order) -> parseId(order.id()));

    private final OrdersPlugin plugin;
    private final Random random = new Random();

    public OrdersService(OrdersPlugin plugin) {
        this.plugin = plugin;
    }

    public static int refreshSeconds() {
        return BOARD_REFRESH_SECONDS;
    }

    /* --------------------------------- доска ---------------------------------- */

    /** Заказы на доске в порядке номеров. */
    public List<BotOrder> board() {
        List<BotOrder> orders = new ArrayList<>(plugin.store().orders());
        orders.sort(BY_ID);
        return List.copyOf(orders);
    }

    public BotOrder order(String id) {
        return plugin.store().order(id);
    }

    /**
     * Снимает истёкшие заказы и добирает доску до {@code board.size}.
     *
     * @return сколько заказов добавлено
     */
    public int ensureBoard() {
        int removed = purgeExpired();
        if (removed > 0) {
            plugin.getLogger().info("С доски ушло заказов: " + removed + ".");
        }
        if (!plugin.config().enabled()) {
            return 0;
        }
        int added = 0;
        int target = plugin.config().boardSize();
        while (plugin.store().size() < target) {
            if (create(null) == null) {
                plugin.getLogger().warning("Доска не добрана: проверьте ботов и категории в config.yml.");
                break;
            }
            added++;
        }
        return added;
    }

    /** Снимает истёкшие заказы; возвращает число снятых. */
    public int purgeExpired() {
        return plugin.store().purgeExpired(System.currentTimeMillis());
    }

    public int clearBoard() {
        int removed = plugin.store().clear();
        if (removed > 0) {
            plugin.requestSave();
        }
        return removed;
    }

    /**
     * Создаёт один заказ и кладёт его на доску.
     *
     * @param categoryId категория или {@code null} — случайная по весам
     * @return заказ или {@code null}, если конфиг не даёт ни одного варианта
     */
    public BotOrder create(String categoryId) {
        if (!plugin.config().enabled()) {
            return null;
        }
        OrderBoard.Draft draft;
        if (categoryId == null || categoryId.isBlank()) {
            draft = OrderBoard.draft(plugin.config().bots(), plugin.config().categories(),
                    plugin.config().premium(), plugin.config().jitterPercent(),
                    plugin.config().minReward(), plugin.config().maxReward(), random);
        } else {
            draft = forcedDraft(categoryId.trim().toLowerCase(Locale.ROOT));
        }
        if (draft == null) {
            return null;
        }
        BotOrder order = OrderBoard.toOrder(draft, plugin.store().reserveId(),
                System.currentTimeMillis(), plugin.config().orderTtlMillis(), random);
        if (order == null) {
            return null;
        }
        plugin.store().add(order);
        plugin.requestSave();
        return order;
    }

    /** Заказ конкретной категории — для {@code /ordersadmin new <категория>}. */
    private OrderBoard.Draft forcedDraft(String categoryId) {
        OrdersConfig config = plugin.config();
        OrderCategory category = config.category(categoryId);
        if (category == null || !category.usable()) {
            return null;
        }
        OrderItem item = category.pick(random);
        if (item == null) {
            return null;
        }
        int amount = item.amount(random);
        int reward = OrderBoard.reward(item, amount, config.premium(), config.jitterPercent(),
                config.minReward(), config.maxReward(), random);
        return new OrderBoard.Draft(botFor(category), category, item, amount, reward,
                OrderBoard.passDays(category, random));
    }

    /**
     * Заказчик для принудительной категории: сначала бот, который такие заказы
     * и так берёт; если такого нет — любой бот, чтобы админская команда не
     * зависела от правок конфига.
     */
    private OrderBot botFor(OrderCategory category) {
        List<OrderBot> bots = plugin.config().bots();
        if (bots.isEmpty()) {
            return new OrderBot("board", "Доска заказов", List.of(category.id()), List.of());
        }
        List<OrderBot> matching = new ArrayList<>();
        for (OrderBot bot : bots) {
            if (bot.categories().contains(category.id())) {
                matching.add(bot);
            }
        }
        List<OrderBot> pool = matching.isEmpty() ? bots : matching;
        return pool.get(random.nextInt(pool.size()));
    }

    /** Сколько заказов игрок вправе взять с учётом репутации. */
    public int availableFor(Player player) {
        int reputation = reputation(player);
        int count = 0;
        for (BotOrder order : board()) {
            if (order.minReputation() <= reputation) {
                count++;
            }
        }
        return count;
    }

    /* ------------------------------- сдача ------------------------------------ */

    /**
     * Проверяет и выполняет сдачу заказа.
     *
     * <p>Все сообщения отправляются здесь же: команда и меню вызывают один и
     * тот же путь, поэтому правила не могут разойтись между ними.</p>
     *
     * @return {@code true}, если предметы приняты и награда выдана
     */
    public boolean deliver(Player player, String orderId) {
        if (player == null) {
            return false;
        }
        BotOrder order = order(orderId);
        if (order == null) {
            plugin.messages().send(player, "unknown-order", "id", orderId == null ? "" : orderId);
            return false;
        }
        return deliver(player, order);
    }

    public boolean deliver(Player player, BotOrder order) {
        if (player == null || order == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (order.expired(now) || plugin.store().order(order.id()) == null) {
            plugin.messages().send(player, "order-expired");
            ensureBoard();
            return false;
        }
        Material material = order.materialOrNull();
        if (material == null) {
            // Материал исчез вместе с версией игры: заказ снимаем, чтобы он не висел мёртвым.
            plugin.store().remove(order.id());
            plugin.messages().send(player, "order-expired");
            ensureBoard();
            return false;
        }
        int reputation = reputation(player);
        if (order.minReputation() > reputation) {
            plugin.messages().send(player, "locked", "need", String.valueOf(order.minReputation()));
            return false;
        }
        int limit = plugin.config().maxPerDay();
        if (limit > 0 && plugin.store().today(player.getUniqueId(), now) >= limit) {
            plugin.messages().send(player, "daily-limit", "limit", String.valueOf(limit));
            return false;
        }
        long cooldown = cooldownLeft(player);
        if (cooldown > 0L) {
            plugin.messages().send(player, "cooldown",
                    "seconds", String.valueOf((cooldown + 999L) / 1000L));
            return false;
        }
        int have = countPlain(player, material);
        if (have < order.amount()) {
            plugin.messages().send(player, "not-enough",
                    "need", String.valueOf(order.amount()),
                    "material", itemName(material),
                    "have", String.valueOf(have));
            return false;
        }
        if (!takePlain(player, material, order.amount())) {
            // Инвентарь изменился между подсчётом и списанием — ничего не теряем.
            plugin.messages().send(player, "inventory-error");
            return false;
        }

        plugin.store().remove(order.id());
        plugin.store().recordDelivery(player.getUniqueId(), player.getName(), now,
                order.reputation());
        int dropped = payDiamonds(player, order.rewardDiamonds());
        boolean pass = grantPass(player, order);
        plugin.requestSave();

        plugin.messages().send(player, "delivered",
                "material", itemName(material),
                "amount", String.valueOf(order.amount()),
                "bot", order.botName(),
                "reward", String.valueOf(order.rewardDiamonds()));
        String thanks = thanksOf(order);
        if (!thanks.isEmpty()) {
            plugin.messages().send(player, "delivered-line",
                    "bot", order.botName(), "line", thanks);
        }
        if (pass) {
            plugin.messages().send(player, "delivered-pass",
                    "days", String.valueOf(order.rewardPassDays()));
        }
        if (dropped > 0) {
            plugin.messages().send(player, "delivered-dropped");
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.1f);
        if (pass) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                plugin.messages().send(online, "delivered-broadcast",
                        "player", player.getName(),
                        "bot", order.botName(),
                        "reward", String.valueOf(order.rewardDiamonds()));
            }
        }
        ensureBoard();
        return true;
    }

    /** Реплика бота на сдачу: последняя «спасибо» из конфига, иначе пусто. */
    private String thanksOf(BotOrder order) {
        for (OrderBot bot : plugin.config().bots()) {
            if (bot.id().equals(order.botId())) {
                return bot.thanks(random);
            }
        }
        return "";
    }

    /** Выдаёт алмазы; возвращает количество, которое не влезло и упало под ноги. */
    private int payDiamonds(Player player, int total) {
        int remaining = Math.max(0, total);
        int dropped = 0;
        while (remaining > 0) {
            int chunk = Math.min(DIAMOND_STACK, remaining);
            remaining -= chunk;
            ItemStack stack = new ItemStack(Material.DIAMOND, chunk);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
                dropped += rest.getAmount();
            }
        }
        return dropped;
    }

    /** Дни проходки через ядро; {@code true} — награда выдана. */
    private boolean grantPass(Player player, BotOrder order) {
        if (!order.hasPassReward()) {
            return false;
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            plugin.getLogger().warning("Ядро недоступно: проходка за заказ " + order.id()
                    + " не выдана игроку " + player.getName() + ".");
            return false;
        }
        api.grantPass(player.getName(), order.rewardPassDays(), "orders:" + order.botId());
        return true;
    }

    /* ------------------------------ предметы ---------------------------------- */

    /**
     * «Простой» предмет: складывается с обычным стаком того же материала.
     *
     * <p>Сравнение через {@link ItemStack#isSimilar(ItemStack)} — та же проверка,
     * по которой игра объединяет стаки. Значит, имя, чары, лор, PDC и прочие
     * компоненты автоматически делают предмет «особенным». Урон проверяем
     * отдельно: в современных версиях он лежит в компоненте предмета, а не в
     * {@link ItemMeta}, и {@code isSimilar} его не видит.</p>
     */
    public static boolean isPlain(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable damageable && damageable.getDamage() > 0) {
            return false;
        }
        ItemStack plain = new ItemStack(stack.getType());
        return stack.isSimilar(plain);
    }

    /** Сколько простых предметов этого материала лежит в инвентаре. */
    public static int countPlain(Player player, Material material) {
        if (player == null || material == null) {
            return 0;
        }
        int count = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack stack : contents) {
            if (stack != null && stack.getType() == material && isPlain(stack)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Списывает ровно {@code amount} простых предметов; {@code false} — не хватило. */
    private static boolean takePlain(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material || !isPlain(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            remaining -= take;
            if (take >= stack.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                player.getInventory().setItem(slot, stack);
            }
        }
        return remaining == 0;
    }

    /* ------------------------------ репутация --------------------------------- */

    public int reputation(Player player) {
        return player == null ? 0 : plugin.store().reputation(player.getUniqueId());
    }

    public String rankId(int reputation) {
        return plugin.config().rankId(reputation);
    }

    /** Русское имя звания (с MiniMessage) — для чата; без разметки — для плейсхолдеров. */
    public String rankText(int reputation) {
        return plugin.messages().raw("rank." + rankId(reputation));
    }

    /** Сколько репутации осталось до следующего звания; 0 — выше некуда. */
    public int reputationToNext(int reputation) {
        int next = plugin.config().nextRankThreshold(reputation);
        return next < 0 ? 0 : Math.max(0, next - reputation);
    }

    /** Пауза между сдачами в миллисекундах; 0 — можно сдавать. */
    public long cooldownLeft(Player player) {
        if (player == null) {
            return 0L;
        }
        int seconds = plugin.config().cooldownSeconds();
        if (seconds <= 0) {
            return 0L;
        }
        long last = plugin.store().lastDelivery(player.getUniqueId());
        if (last <= 0L) {
            return 0L;
        }
        return Math.max(0L, last + seconds * 1000L - System.currentTimeMillis());
    }

    /** Сколько заказов игрок сдал сегодня (сутки UTC). */
    public int today(Player player) {
        return player == null ? 0
                : plugin.store().today(player.getUniqueId(), System.currentTimeMillis());
    }

    /* ------------------------------- отображение ------------------------------- */

    /** Название материала тегом клиента — так оно локализовано у игрока. */
    public static Messages.Trusted itemName(Material material) {
        if (material == null) {
            return Messages.trusted("");
        }
        String path = material.name().toLowerCase(Locale.ROOT);
        String namespace = material.isBlock() ? "block.minecraft." : "item.minecraft.";
        return Messages.trusted("<lang:" + namespace + path + ">");
    }

    /** Компонент с названием материала — для меню. */
    public Component itemComponent(Material material) {
        if (material == null) {
            return Component.empty();
        }
        String path = material.name().toLowerCase(Locale.ROOT);
        String namespace = material.isBlock() ? "block.minecraft." : "item.minecraft.";
        return plugin.messages().renderTemplate("<lang:" + namespace + path + ">");
    }

    static long parseId(String id) {
        try {
            return Long.parseLong(id == null ? "" : id.trim());
        } catch (NumberFormatException exception) {
            return Long.MAX_VALUE;
        }
    }
}
