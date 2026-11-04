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

/** Снимает ванильный предел 40, не обрезая настоящую стоимость. */
final class AnvilCostListener implements Listener {
    private final java.util.function.Supplier<Messages> texts;
    private final java.util.function.Consumer<ru.cherdakmd.chrdkregister.api.events.AnvilPaidResultEvent> paid;
    private final java.util.function.Predicate<Player> authenticated;
    private final java.util.function.BiPredicate<AnvilView,Player> accessible;
    private final java.util.function.BiConsumer<AnvilView,Player> finish;
    private final java.util.function.Consumer<RuntimeException> failureLog;
    AnvilCostListener(RegisterPlugin plugin) {
        this(plugin::messages, player -> {
            var api=plugin.getServer().getServicesManager().load(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class);
            return api!=null && api.isAuthenticated(player);
        },AnvilUse::accessible,AnvilUse::finish,
                failure -> plugin.getLogger().log(java.util.logging.Level.SEVERE,"Наковальня: откат незавершённого применения",failure),
                event -> event.callEvent());
    }
    /** Адаптеры платформы отделены от самого обработчика для проверки реального take/rollback без сервера. */
    AnvilCostListener(java.util.function.Supplier<Messages> texts,java.util.function.Predicate<Player> authenticated,
                      java.util.function.BiPredicate<AnvilView,Player> accessible,
                      java.util.function.BiConsumer<AnvilView,Player> finish,
                      java.util.function.Consumer<RuntimeException> failureLog,
                      java.util.function.Consumer<ru.cherdakmd.chrdkregister.api.events.AnvilPaidResultEvent> paid) {
        this.texts=texts; this.authenticated=authenticated; this.accessible=accessible;
        this.finish=finish; this.failureLog=failureLog; this.paid=paid;
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
        if (AnvilCosts.remember(view,event.getResult()) && view.getPlayer() instanceof Player player)
            messages().send(player,"anvil.full-price","xp",AnvilCosts.actual(view));
    }
    @EventHandler public void close(InventoryCloseEvent event) { if (event.getView() instanceof AnvilView view) AnvilCosts.clear(view); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void take(InventoryClickEvent event) {
        if (event.isCancelled() || !(event.getView() instanceof AnvilView view) || event.getRawSlot()!=2 || !(event.getWhoClicked() instanceof Player player)) return;
        var quote = AnvilCosts.quote(view);
        if (quote == null || quote.price()<40 || player.getGameMode()==GameMode.CREATIVE) return;
        event.setCancelled(true);
        // Никакого бесплатного дорогого результата, даже если клиент видит только 39.
        if (quote.left()==null || quote.left().isEmpty() || quote.result()==null || !quote.matches(view) || !accessible.test(view,player) || !AnvilUse.usesLevels(player.getGameMode())
                || !player.isOnline() || player.isDead() || !authenticated.test(player) || player.getLevel()<quote.price()
                || event.getClick()!=ClickType.LEFT || !event.getCursor().isEmpty()) {
            messages().send(player,"anvil.take-help","xp",quote.price()); return;
        }
        ItemStack rest=quote.right()==null?null:quote.right().clone();
        if (rest!=null) {
            int used=quote.materials()>0?quote.materials():rest.getAmount();
            if (used>rest.getAmount()) return;
            rest.setAmount(rest.getAmount()-used); if (rest.getAmount()==0) rest=null;
        }
        int level=player.getLevel(), total=player.getTotalExperience(); float exp=player.getExp();
        AnvilCosts.clear(view);
        try {
            view.getTopInventory().setItem(2,null);
            view.getTopInventory().setItem(0,null);
            view.getTopInventory().setItem(1,rest);
            player.giveExpLevels(-quote.price());
            player.setItemOnCursor(quote.result().clone());
            view.setRepairCost(-1);
        } catch (RuntimeException failure) {
            player.setItemOnCursor(null);
            view.getTopInventory().setItem(0,quote.left()); view.getTopInventory().setItem(1,quote.right());
            view.getTopInventory().setItem(2,null); AnvilCosts.clear(view); view.setRepairCost(-1);
            player.setLevel(level); player.setTotalExperience(total); player.setExp(exp);
            failureLog.accept(failure);
            player.updateInventory(); return;
        }
        player.updateInventory();
        paid.accept(new ru.cherdakmd.chrdkregister.api.events.AnvilPaidResultEvent(player,view,quote.left(),quote.right(),quote.result(),quote.price()));
        finish.accept(view,player);
    }
}
