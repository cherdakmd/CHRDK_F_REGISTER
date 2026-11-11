package ru.cherdakmd.chrdkregister;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import ru.cherdakmd.chrdkregister.api.AnvilCosts;
import ru.cherdakmd.chrdkregister.api.AnvilUse;
import ru.cherdakmd.chrdkregister.api.events.AnvilPaidResultEvent;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** Снимает ванильный предел 40, не обрезая настоящую стоимость.
 *
 * <p>Выше {@link AnvilSettings#maxLevelPrice()} опыт уже не списывается: операцию
 * можно оплатить алмазами с {@code /bal}, если {@code anvil.currency-payment}
 * включён. Накопленный {@code RepairCost} предметов не переписывается — меняется
 * только способ оплаты дорогого результата.</p> */
final class AnvilCostListener implements Listener {
    private final Supplier<Messages> texts;
    private final Consumer<AnvilPaidResultEvent> paid;
    private final Predicate<Player> authenticated;
    private final BiPredicate<AnvilView,Player> accessible;
    private final BiConsumer<AnvilView,Player> finish;
    private final Consumer<RuntimeException> failureLog;
    private final Supplier<AnvilSettings> settings;
    /** Текущий баланс игрока в алмазах ({@code /bal}). */
    private final ToLongFunction<Player> balance;
    /** Атомарное списание {@code diamonds}; false — денег нет или банк недоступен. */
    private final BiPredicate<Player,Long> charge;
    /** Возврат списания, если выдача результата провалилась после него. */
    private final BiConsumer<Player,Long> refund;
    private final ToLongFunction<Long> marketPrice;
    private final Function<Long,String> moneyText;

    AnvilCostListener(RegisterPlugin plugin) {
        this(plugin::messages, player -> {
            var api=plugin.getServer().getServicesManager().load(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class);
            return api!=null && api.isAuthenticated(player);
        },AnvilUse::accessible,AnvilUse::finish,
                failure -> plugin.getLogger().log(java.util.logging.Level.SEVERE,"Наковальня: откат незавершённого применения",failure),
                event -> event.callEvent(),
                () -> {
                    var config=plugin.config();
                    return config==null ? AnvilSettings.DEFAULTS : config.anvil();
                },
                player -> {
                    var bank=plugin.bank();
                    return bank==null || !bank.enabled() ? 0L : bank.store().balanceMinor(player.getUniqueId());
                },
                (player,diamonds) -> {
                    var bank=plugin.bank();
                    return bank!=null && bank.enabled() && bank.store().withdrawMinor(player.getUniqueId(),diamonds,true);
                },
                (player,diamonds) -> {
                    var bank=plugin.bank();
                    if (bank!=null) bank.store().depositMinor(player.getUniqueId(),player.getName(),diamonds,true);
                },
                base -> {
                    var bank=plugin.bank();
                    return bank==null ? base : bank.marketQuote().scaleMinor(Math.multiplyExact(base,100L));
                },
                ru.cherdakmd.chrdkregister.api.DiamondMoney::format);
    }

    /** Адаптеры платформы отделены от самого обработчика для проверки реального take/rollback без сервера. */
    AnvilCostListener(Supplier<Messages> texts, Predicate<Player> authenticated,
                      BiPredicate<AnvilView,Player> accessible,
                      BiConsumer<AnvilView,Player> finish,
                      Consumer<RuntimeException> failureLog,
                      Consumer<AnvilPaidResultEvent> paid) {
        this(texts,authenticated,accessible,finish,failureLog,paid,
                () -> AnvilSettings.UNLIMITED, player -> 0L,
                (player,diamonds) -> false, (player,diamonds) -> { });
    }

    AnvilCostListener(Supplier<Messages> texts, Predicate<Player> authenticated,
                      BiPredicate<AnvilView,Player> accessible,
                      BiConsumer<AnvilView,Player> finish,
                      Consumer<RuntimeException> failureLog,
                      Consumer<AnvilPaidResultEvent> paid,
                      Supplier<AnvilSettings> settings, ToLongFunction<Player> balance,
                      BiPredicate<Player,Long> charge, BiConsumer<Player,Long> refund) {
        this(texts,authenticated,accessible,finish,failureLog,paid,settings,balance,charge,refund,
                base -> base,String::valueOf);
    }

    AnvilCostListener(Supplier<Messages> texts, Predicate<Player> authenticated,
                      BiPredicate<AnvilView,Player> accessible,
                      BiConsumer<AnvilView,Player> finish,
                      Consumer<RuntimeException> failureLog,
                      Consumer<AnvilPaidResultEvent> paid,
                      Supplier<AnvilSettings> settings, ToLongFunction<Player> balance,
                      BiPredicate<Player,Long> charge, BiConsumer<Player,Long> refund,
                      ToLongFunction<Long> marketPrice, Function<Long,String> moneyText) {
        this.texts=texts; this.authenticated=authenticated; this.accessible=accessible;
        this.finish=finish; this.failureLog=failureLog; this.paid=paid;
        this.settings=settings; this.balance=balance; this.charge=charge; this.refund=refund;
        this.marketPrice=marketPrice; this.moneyText=moneyText;
    }

    private Messages messages() { return texts.get(); }

    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void open(InventoryOpenEvent event) {
        if (event.getView() instanceof AnvilView view) view.setMaximumRepairCost(Integer.MAX_VALUE);
    }
    @EventHandler(priority=EventPriority.LOWEST) public void ceiling(PrepareAnvilEvent event) {
        event.getView().setMaximumRepairCost(Integer.MAX_VALUE);
        AnvilCosts.capRename(event.getView(),event.getResult());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void prepare(PrepareAnvilEvent event) {
        var view = event.getView();
        if (event.getResult()==null && view.getRepairCost()>=40) {
            AnvilCosts.clear(view); view.setRepairCost(-1); return;
        }
        AnvilSettings current = settings.get();
        // actual() до remember(): после него view.getRepairCost() уже равен display(..) <= 39.
        int full = AnvilCosts.actual(view,event.getResult());
        String notice = noticeKey(current,full);
        if ("anvil.too-expensive".equals(notice)) {
            // Единый потолок без валютной альтернативы — «слишком дорого», опыт не тронут.
            AnvilCosts.clear(view); event.setResult(null); view.setRepairCost(-1);
            if (view.getPlayer() instanceof Player player)
                messages().send(player,"anvil.too-expensive","xp",full,"limit",current.maxLevelPrice());
            return;
        }
        // remember() возвращает true только при смене котировки: предупреждение не спамит на каждый репрепайр.
        if (AnvilCosts.remember(view,event.getResult()) && view.getPlayer() instanceof Player player) {
            if ("anvil.currency-price".equals(notice))
                messages().send(player,"anvil.currency-price","xp",full,
                        "diamonds",moneyText.apply(marketPrice.applyAsLong(current.diamonds(full))),
                        "limit",current.maxLevelPrice(),"balance",moneyText.apply(balance.applyAsLong(player)));
            else if ("anvil.full-price".equals(notice))
                messages().send(player,"anvil.full-price","xp",AnvilCosts.actual(view));
        }
    }

    /** Какой чат-ключ показывает prepare: null для нативной цены < 40. Вынесено для проверки без Bukkit-события. */
    static String noticeKey(AnvilSettings current,int fullPrice) {
        if (fullPrice < 40) return null;
        if (fullPrice > current.maxLevelPrice())
            return current.currencyPayment() ? "anvil.currency-price" : "anvil.too-expensive";
        return "anvil.full-price";
    }
    @EventHandler public void close(InventoryCloseEvent event) { if (event.getView() instanceof AnvilView view) AnvilCosts.clear(view); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void take(InventoryClickEvent event) {
        if (event.isCancelled() || !(event.getView() instanceof AnvilView view) || event.getRawSlot()!=2 || !(event.getWhoClicked() instanceof Player player)) return;
        var quote = AnvilCosts.quote(view);
        if (quote == null || quote.price()<40 || player.getGameMode()==GameMode.CREATIVE) return;
        event.setCancelled(true);
        AnvilSettings current = settings.get();
        long baseDiamonds = current.diamonds(quote.price());
        long diamonds = marketPrice.applyAsLong(baseDiamonds);
        boolean currency = diamonds>0L;
        // Никакого бесплатного дорогого результата, даже если клиент видит только 39.
        if (quote.left()==null || quote.left().isEmpty() || quote.result()==null || !quote.matches(view) || !accessible.test(view,player) || !AnvilUse.usesLevels(player.getGameMode())
                || !player.isOnline() || player.isDead() || !authenticated.test(player)
                || event.getClick()!=ClickType.LEFT || !event.getCursor().isEmpty()) {
            help(player,quote.price(),diamonds); return;
        }
        if (currency) {
            if (balance.applyAsLong(player)<diamonds) { help(player,quote.price(),diamonds); return; }
        } else if (player.getLevel()<quote.price()) {
            help(player,quote.price(),diamonds); return;
        }
        ItemStack rest=quote.right()==null?null:quote.right().clone();
        if (rest!=null) {
            int used=quote.materials()>0?quote.materials():rest.getAmount();
            if (used>rest.getAmount()) return;
            rest.setAmount(rest.getAmount()-used); if (rest.getAmount()==0) rest=null;
        }
        int level=player.getLevel(), total=player.getTotalExperience(); float exp=player.getExp();
        AnvilCosts.clear(view);
        boolean charged=false;
        try {
            view.getTopInventory().setItem(2,null);
            view.getTopInventory().setItem(0,null);
            view.getTopInventory().setItem(1,rest);
            if (currency) {
                if (!charge.test(player,diamonds)) throw new IllegalStateException("Не удалось списать алмазы с /bal");
                charged=true;
            } else {
                player.giveExpLevels(-quote.price());
            }
            player.setItemOnCursor(quote.result().clone());
            view.setRepairCost(-1);
        } catch (RuntimeException failure) {
            if (charged) refund.accept(player,diamonds);
            player.setItemOnCursor(null);
            view.getTopInventory().setItem(0,quote.left()); view.getTopInventory().setItem(1,quote.right());
            view.getTopInventory().setItem(2,null); AnvilCosts.clear(view); view.setRepairCost(-1);
            player.setLevel(level); player.setTotalExperience(total); player.setExp(exp);
            failureLog.accept(failure);
            player.updateInventory(); return;
        }
        player.updateInventory();
        paid.accept(currency
                ? new AnvilPaidResultEvent(player,view,quote.left(),quote.right(),quote.result(),0,baseDiamonds)
                : new AnvilPaidResultEvent(player,view,quote.left(),quote.right(),quote.result(),quote.price()));
        finish.accept(view,player);
        if (currency) messages().send(player,"anvil.currency-paid","diamonds",moneyText.apply(diamonds),"xp",quote.price());
    }

    private void help(Player player,int price,long diamonds) {
        if (diamonds>0L)
            messages().send(player,"anvil.currency-help","diamonds",moneyText.apply(diamonds),
                    "balance",moneyText.apply(balance.applyAsLong(player)),"xp",price);
        else messages().send(player,"anvil.take-help","xp",price);
    }
}
