package ru.cherdakmd.chrdkregister.api;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Полная серверная цена при ограниченном клиентском счётчике. Только главный поток. */
public final class AnvilCosts {
    private AnvilCosts() { }
    private static final Map<AnvilView, Quote> QUOTES = new WeakHashMap<>();
    private static final Map<AnvilView, Integer> WRITTEN = new WeakHashMap<>();
    public record Quote(ItemStack left, ItemStack right, ItemStack result, String rename, int price, int materials,
                        int maximum, org.bukkit.GameMode mode) {
        public boolean matches(AnvilView view) {
            return Objects.equals(left,view.getTopInventory().getItem(0)) && Objects.equals(right,view.getTopInventory().getItem(1))
                    && Objects.equals(result,view.getTopInventory().getItem(2)) && Objects.equals(rename,view.getRenameText())
                    && view.getRepairCost() == display(price) && view.getRepairItemCountCost() == materials
                    && view.getMaximumRepairCost() == maximum && view.getPlayer().getGameMode() == mode;
        }
    }
    public static int renamePrice(int price,boolean onlyRename) { return onlyRename ? display(price) : price; }
    public static int display(int price) { return price < 0 ? price : Math.min(39,price); }
    public static int actual(AnvilView view) { return actual(view,view.getTopInventory().getItem(2)); }
    public static int actual(AnvilView view, ItemStack preview) {
        Integer written = WRITTEN.get(view);
        if (written != null && written == view.getRepairCost()) return written;
        Quote quote = QUOTES.get(view);
        return quote != null && quote.matches(view) && Objects.equals(quote.result(),preview) ? quote.price() : view.getRepairCost();
    }
    /** Потребитель (например чары) явно сообщает новую полную цену, даже если она равна 39. */
    public static void price(AnvilView view, int price) { QUOTES.remove(view); WRITTEN.put(view,price); view.setRepairCost(price); }
    /** ванильное исключение rename-only остаётся 39, а не maxInt-1. */
    public static void capRename(AnvilView view,ItemStack preview) {
        if (preview!=null && view.getRepairCost()>=40 && onlyRename(view,preview)) price(view,39);
    }
    /** Скидка до сборки custom-результата; повтор готового preview не скидируется второй раз. */
    public static void discount(AnvilView view,ItemStack preview,int percent) {
        Quote previous=QUOTES.get(view);
        if (previous!=null && previous.matches(view) && Objects.equals(previous.result(),preview)) return;
        int full=actual(view,preview);
        if (full<=0 || percent<=0) return;
        int discounted=(int)Math.round((long)full*(100-Math.min(100,percent))/100.0);
        if (discounted<full) price(view,discounted);
    }
    public static Quote quote(AnvilView view) { return QUOTES.get(view); }
    public static boolean remember(AnvilView view, ItemStack result) {
        int cost = actual(view,result);
        WRITTEN.remove(view);
        if (result == null || result.isEmpty() || cost < 0) { QUOTES.remove(view); return false; }
        // Ванильное исключение для одного переименования остаётся не дороже 39.
        cost=renamePrice(cost,cost>=40 && onlyRename(view,result));
        Quote previous = QUOTES.get(view);
        var quote = new Quote(copy(view.getTopInventory().getItem(0)),copy(view.getTopInventory().getItem(1)),copy(result),
                view.getRenameText(),cost,view.getRepairItemCountCost(),view.getMaximumRepairCost(),view.getPlayer().getGameMode());
        QUOTES.put(view,quote);
        view.setRepairCost(display(cost));
        return cost >= 40 && !quote.equals(previous);
    }
    private static boolean onlyRename(AnvilView view,ItemStack result) {
        var left=view.getTopInventory().getItem(0);
        if (left==null || view.getTopInventory().getItem(1)!=null || view.getRenameText()==null) return false;
        var before=left.getItemMeta(); var after=result.getItemMeta();
        if (before==null || after==null || Objects.equals(before.displayName(),after.displayName())) return false;
        ItemStack renamed=left.clone(); before.displayName(after.displayName()); renamed.setItemMeta(before);
        return renamed.equals(result); // Только имя, без скрытого изменения PDC/чар/количества/прочности.
    }
    private static ItemStack copy(ItemStack item) { return item == null ? null : item.clone(); }
    public static void clear(AnvilView view) { QUOTES.remove(view); WRITTEN.remove(view); }
    public static void clearAll() { QUOTES.clear(); WRITTEN.clear(); }
}
