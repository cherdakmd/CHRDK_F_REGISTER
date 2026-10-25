package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Банк алмазов: депозит, снятие, переводы и топ.
 *
 * <p>Единственное место, где встречаются инвентарь и счёт: все операции
 * выполняются на главном потоке целиком, поэтому «списали, но не выдали»
 * невозможно. Депозит принимает только обычные алмазы и алмазные блоки —
 * именованные и зачарованные предметы не трогаются (их можно продать, но не
 * «разменять» случайно). Снятие сначала проверяет место в инвентаре и только
 * потом списывает.</p>
 *
 * <p>Перевод идёт по счёту, а не предметами: получатель может быть офлайн, а
 * сумма не зависит от свободных слотов. Комиссия ({@code economy.pay-tax-percent})
 * не зачисляется никому — это не доход сервера, а сдерживающий налог.</p>
 */
public final class BankService {

    /** Результат снятия. */
    public enum WithdrawStatus {
        OK,
        NOT_ENOUGH,
        NO_SPACE
    }

    public record WithdrawResult(WithdrawStatus status, long amount, long balance) {
    }

    /** Результат перевода для командного слоя. */
    public record PayResult(BankStore.Status status, String targetName, long amount, long tax,
                           long credited, long fromBalance, long cooldownSeconds) {
        public boolean ok() {
            return status == BankStore.Status.OK;
        }
    }

    private final RegisterPlugin plugin;
    private final BankStore store;
    private final Supplier<EconomySettings> settings;
    /** Пауза между переводами: uuid → момент последнего перевода (мс). */
    private final Map<UUID, Long> lastPay = new ConcurrentHashMap<>();

    /** Ленивая инициализация: юнит-тесты разбора сумм не должны трогать Bukkit-предметы. */
    private static final class Plain {
        private static final ItemStack DIAMOND = new ItemStack(Material.DIAMOND);
        private static final ItemStack BLOCK = new ItemStack(Material.DIAMOND_BLOCK);

        private Plain() {
        }
    }

    public BankService(RegisterPlugin plugin, BankStore store, Supplier<EconomySettings> settings) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
    }

    public BankStore store() {
        return store;
    }

    public EconomySettings settings() {
        return settings.get();
    }

    public boolean enabled() {
        return settings().enabled();
    }

    /* ---------------------------------- счёт ---------------------------------- */

    /** Открывает счёт при первом обращении и обновляет ник. */
    public long balance(Player player) {
        if (player == null) {
            return 0L;
        }
        return store.open(player.getUniqueId(), player.getName()).balance;
    }

    /** Баланс по нику (онлайн или сохранённый офлайн-счёт). */
    public long balanceOf(String name) {
        return store.balanceOf(name);
    }

    /** Счёт существует и имя известно банку. */
    public boolean known(String name) {
        return store.idByName(name) != null;
    }

    /* -------------------------------- депозит --------------------------------- */

    /**
     * Складывает алмазы в банк.
     *
     * @param handOnly только предмет в руке (true) или весь инвентарь (false)
     * @return сколько алмазов зачислено
     */
    public long deposit(Player player, boolean handOnly) {
        if (player == null || !enabled()) {
            return 0L;
        }
        long worth = 0L;
        var inventory = player.getInventory();
        if (handOnly) {
            ItemStack hand = inventory.getItemInMainHand();
            long value = valueOf(hand);
            if (value > 0L) {
                worth = value;
                inventory.setItemInMainHand(null);
            }
        } else {
            ItemStack[] contents = inventory.getStorageContents();
            boolean changed = false;
            for (int slot = 0; slot < contents.length; slot++) {
                long value = valueOf(contents[slot]);
                if (value <= 0L) {
                    continue;
                }
                worth += value;
                contents[slot] = null;
                changed = true;
            }
            if (changed) {
                inventory.setStorageContents(contents);
            }
        }
        if (worth <= 0L) {
            return 0L;
        }
        long balance = store.deposit(player.getUniqueId(), player.getName(), worth);
        if (balance < 0L) {
            // Счёт переполнен — возвращаем предметы, ничего не теряя.
            refund(player, worth);
            return 0L;
        }
        return worth;
    }

    /**
     * Складывает в банк ровно {@code amount} алмазов, снимая только обычные
     * алмазы из инвентаря. Блоки не размениваются: игрок сам выбирает, чем
     * платить, и не получает «сдачу» незаметно для себя.
     *
     * @return сколько зачислено (0 — не хватило алмазов)
     */
    public long depositAmount(Player player, long amount) {
        if (player == null || amount <= 0L || !enabled()) {
            return 0L;
        }
        long available = 0L;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(Plain.DIAMOND)) {
                available += stack.getAmount();
            }
        }
        if (available < amount) {
            return 0L;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        long left = amount;
        for (int slot = 0; slot < contents.length && left > 0L; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.isSimilar(Plain.DIAMOND)) {
                continue;
            }
            int take = (int) Math.min(left, stack.getAmount());
            int rest = stack.getAmount() - take;
            contents[slot] = rest == 0 ? null : new ItemStack(Material.DIAMOND, rest);
            left -= take;
        }
        player.getInventory().setStorageContents(contents);
        long balance = store.deposit(player.getUniqueId(), player.getName(), amount);
        if (balance < 0L) {
            refund(player, amount);
            return 0L;
        }
        return amount;
    }

    /** Алмазный эквивалент предмета (обычный алмаз/блок) или 0. */
    private long valueOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 0L;
        }
        if (stack.isSimilar(Plain.DIAMOND)) {
            return stack.getAmount();
        }
        if (stack.isSimilar(Plain.BLOCK)) {
            return (long) stack.getAmount() * settings().blockWorth();
        }
        return 0L;
    }

    /** Возврат предметов при сбое (переполнение счёта): лучше выдать, чем потерять. */
    private void refund(Player player, long diamonds) {
        long left = diamonds;
        while (left > 0L) {
            int take = (int) Math.min(64L, left);
            left -= take;
            var leftover = player.getInventory().addItem(new ItemStack(Material.DIAMOND, take));
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
    }

    /* --------------------------------- снятие --------------------------------- */

    /** Снимает алмазы со счёта в инвентарь (только если хватает места). */
    public WithdrawResult withdraw(Player player, long amount) {
        if (player == null || amount <= 0L) {
            return new WithdrawResult(WithdrawStatus.NOT_ENOUGH, Math.max(0L, amount), 0L);
        }
        long balance = store.balance(player.getUniqueId());
        if (balance < amount) {
            return new WithdrawResult(WithdrawStatus.NOT_ENOUGH, amount, balance);
        }
        if (capacity(player) < amount) {
            return new WithdrawResult(WithdrawStatus.NO_SPACE, amount, balance);
        }
        if (!store.withdraw(player.getUniqueId(), amount)) {
            return new WithdrawResult(WithdrawStatus.NOT_ENOUGH, amount, store.balance(player.getUniqueId()));
        }
        long left = amount;
        while (left > 0L) {
            int take = (int) Math.min(64L, left);
            left -= take;
            var leftover = player.getInventory().addItem(new ItemStack(Material.DIAMOND, take));
            for (ItemStack stack : leftover.values()) {
                // Место проверяли заранее; падение — страховка, а не норма.
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
        return new WithdrawResult(WithdrawStatus.OK, amount, store.balance(player.getUniqueId()));
    }

    /** Сколько алмазов влезает в инвентарь с учётом неполных стаков обычных алмазов. */
    public static long capacity(Player player) {
        long capacity = 0L;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                capacity += 64L;
            } else if (stack.isSimilar(Plain.DIAMOND)) {
                capacity += Math.max(0, 64 - stack.getAmount());
            }
        }
        return capacity;
    }

    /* -------------------------------- переводы -------------------------------- */

    /** Остаток паузы в секундах (0 — можно платить). */
    public long cooldownLeft(Player player) {
        int cooldown = settings().payCooldownSeconds();
        if (cooldown <= 0 || player == null) {
            return 0L;
        }
        Long last = lastPay.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        long passed = System.currentTimeMillis() - last;
        long left = cooldown * 1000L - passed;
        return left <= 0L ? 0L : (left + 999L) / 1000L;
    }

    /**
     * Перевод другому игроку.
     *
     * @param targetName ник получателя (онлайн или сохранённый в банке)
     */
    public PayResult pay(Player source, String targetName, long amount) {
        EconomySettings config = settings();
        String name = targetName == null ? "" : targetName.trim();
        if (source == null || name.isEmpty()) {
            return new PayResult(BankStore.Status.UNKNOWN_PLAYER, name, amount, 0, 0, 0, 0);
        }
        long cooldown = cooldownLeft(source);
        if (cooldown > 0L) {
            return new PayResult(BankStore.Status.COOLDOWN, name, amount, 0, 0, 0, cooldown);
        }
        if (amount < config.minPay() || amount > config.maxPay()) {
            return new PayResult(BankStore.Status.INVALID_AMOUNT, name, amount, 0, 0, 0, 0);
        }
        Player online = Bukkit.getPlayerExact(name);
        UUID targetId;
        String targetDisplay;
        if (online != null) {
            targetId = online.getUniqueId();
            targetDisplay = online.getName();
            store.open(targetId, targetDisplay);
        } else {
            targetId = config.allowOfflinePay() ? store.idByName(name) : null;
            targetDisplay = name;
        }
        if (targetId == null) {
            return new PayResult(BankStore.Status.UNKNOWN_PLAYER, name, amount, 0, 0, 0, 0);
        }
        if (targetId.equals(source.getUniqueId())) {
            return new PayResult(BankStore.Status.SELF_TRANSFER, name, amount, 0, 0, 0, 0);
        }
        store.open(source.getUniqueId(), source.getName());
        BankStore.TransferResult result = store.transfer(source.getUniqueId(), source.getName(),
                targetId, targetDisplay, amount, config.payTaxPercent());
        if (!result.ok()) {
            return new PayResult(result.status(), targetDisplay, amount, result.tax(),
                    result.credited(), result.fromBalance(), 0L);
        }
        lastPay.put(source.getUniqueId(), System.currentTimeMillis());
        return new PayResult(BankStore.Status.OK, targetDisplay, amount, result.tax(),
                result.credited(), result.fromBalance(), 0L);
    }

    /* ---------------------------------- топ ----------------------------------- */

    public List<BankStore.Entry> baltop() {
        return store.top(settings().baltopSize());
    }

    /* -------------------------------- утилиты --------------------------------- */

    /** Разбор суммы: {@code 1 000}, {@code 1k}, {@code 1.5k}, {@code all}. */
    public static long parseAmount(String raw) {
        if (raw == null) {
            return -1L;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        if (value.isEmpty() || value.equals("all") || value.equals("все") || value.equals("всё")) {
            return -1L;
        }
        long multiplier = 1L;
        if (value.endsWith("k") || value.endsWith("к")) {
            multiplier = 1_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m") || value.endsWith("м")) {
            multiplier = 1_000_000L;
            value = value.substring(0, value.length() - 1);
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed <= 0d) {
                return -1L;
            }
            long amount = Math.round(parsed * multiplier);
            return amount <= 0L ? -1L : Math.min(amount, BankStore.MAX_BALANCE);
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    /** «1234» → «1 234» (разряды через узкий пробел — читается в чате). */
    public static String format(long amount) {
        return String.format(Locale.ROOT, "%,d", amount).replace(',', '\u202F');
    }

    /** Планировщик: периодическая запись файла. */
    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (store.isDirty()) {
                store.saveNow();
            }
        }, 100L, 100L);
    }

    public void shutdown() {
        store.flush();
        lastPay.clear();
    }
}
