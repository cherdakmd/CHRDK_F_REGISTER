package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import ru.cherdakmd.chrdkregister.api.AnvilCosts;
import ru.cherdakmd.chrdkregister.api.AnvilUse;
import java.util.*;
import java.util.function.Consumer;

/** Обычные три слота, без escrow и подмены настоящего результата кнопкой выбора.
 * ПКМ результата выбирает следующее свойство; ЛКМ с пустым курсором подтверждает. */
final class ForgeAnvilListener implements Listener {
    private final TierForgePlugin plugin;
    private final Map<UUID, State> states=new HashMap<>();
    private final AnvilNoticeGate notices=new AnvilNoticeGate();
    private static final class State {
        ForgeAnvilInputs<ItemStack> inputs;
        List<String> choices;
        int index;
        ForgeMaterials.Plan plan;
        ItemStack display;
        boolean busy;
    }
    ForgeAnvilListener(TierForgePlugin plugin) { this.plugin=plugin; }
    void close() {
        // Close возвращает исходные материалы, а не выдаёт display-предмет без обработчика.
        for (var id:new ArrayList<>(states.keySet())) {
            var player=Bukkit.getPlayer(id); var state=states.get(id);
            if (player!=null && state!=null && player.getOpenInventory()==state.inputs.view()) player.closeInventory();
            notices.clear(id);
        }
        states.clear();
    }
    private boolean allowed(Player player,AnvilView view) {
        return player.isOnline() && !player.isDead() && player.getGameMode()==GameMode.SURVIVAL
                && player.hasPermission("chrdktierforge.use") && plugin.coreApi()!=null && plugin.coreApi().isAuthenticated(player)
                && plugin.materials().enabled() && plugin.config().anvilEnabled() && AnvilUse.accessible(view,player);
    }
    private List<String> choices(ItemStack left,ForgeMaterial material) {
        if (material==ForgeMaterial.ASCEND || material==ForgeMaterial.SEED || material==ForgeMaterial.REPAIR
                || material==ForgeMaterial.NAME) return List.of("");
        if (left==null || !left.hasItemMeta()) return List.of();
        // 1.5.2: «Забойщик» не предлагаем, если на предмете уже стоит чара
        // массовой добычи (veinminer/excavator) — иначе колонна и площадь/жила
        // складывались бы за один удар.
        boolean megaMining = hasMegaMiningEnchant(left);
        if (material==ForgeMaterial.TEMPER) return plugin.items().upgradesOf(left).entrySet().stream()
                .filter(e -> e.getValue()>0 && e.getValue()<e.getKey().maxLevel() && e.getKey().fits(ItemKind.classify(left.getType().name())))
                .filter(e -> !megaMining || !MiningCompatPolicy.isColumnMiningUpgrade(e.getKey().id()))
                .map(e -> e.getKey().id()).sorted().toList();
        if (material==ForgeMaterial.INSCRIBE) {
            var kind=ItemKind.classify(left.getType().name());
            return ForgeUpgrade.ALL.stream().filter(d -> d.fits(kind) && plugin.items().upgradeLevel(left,d.id())==0)
                    .filter(d -> !megaMining || !MiningCompatPolicy.isColumnMiningUpgrade(d.id()))
                    .map(ForgeUpgrade.Def::id).sorted().toList();
        }
        if (material==ForgeMaterial.REMOVE) return plugin.items().upgradesOf(left).keySet().stream()
                .map(ForgeUpgrade.Def::id).sorted().toList();
        return plugin.items().propertiesOf(left).stream().map(TierProperty::id).sorted().toList();
    }
    private boolean hasMegaMiningEnchant(ItemStack left) {
        var api = plugin.enchantsApi();
        return api != null && MiningCompatPolicy.hasMegaMining(api.enchantsOf(left));
    }

    private String choiceName(ForgeMaterial material,String id) {
        if (material==ForgeMaterial.ASCEND) return plugin.messages().plain("forge-ascend-action");
        if (material==ForgeMaterial.SEED || material==ForgeMaterial.REPAIR || material==ForgeMaterial.NAME)
            return plugin.messages().plain("forge-"+material.id+"-action");
        if (material==ForgeMaterial.REFORGE) {
            var property=TierProperties.byId(id); return property==null?"":property.name();
        }
        return ForgeUpgrade.ALL.stream().filter(d -> d.id().equals(id)).findFirst()
                .map(ForgeUpgrade.Def::displayName).orElse("");
    }
    @EventHandler(priority=EventPriority.HIGH) public void prepare(PrepareAnvilEvent event) {
        var view=event.getView();
        if (!(view.getPlayer() instanceof Player player)) return;
        var left=view.getTopInventory().getItem(0); var right=view.getTopInventory().getItem(1);
        if (plugin.materials().kind(left)==null && plugin.materials().kind(right)==null) {
            states.remove(player.getUniqueId()); notices.clear(player.getUniqueId()); return;
        }
        render(player,view,event::setResult);
    }
    private void render(Player player,AnvilView view,Consumer<ItemStack> result) {
        var inventory=view.getTopInventory(); var left=inventory.getItem(0); var right=inventory.getItem(1);
        result.accept(null); AnvilCosts.clear(view); view.setRepairCost(-1);
        var material=plugin.materials().kind(right);
        if (!allowed(player,view) || left==null || material==null || plugin.materials().kind(left)!=null) {
            states.remove(player.getUniqueId()); return;
        }
        State state=states.get(player.getUniqueId());
        if (state==null || !state.inputs.matches(view,left,right,plugin.config(),view.getRenameText())) {
            state=new State(); state.inputs=new ForgeAnvilInputs<>(view,left.clone(),right.clone(),plugin.config(),view.getRenameText());
            state.choices=choices(left,material); states.put(player.getUniqueId(),state);
        }
        state.plan=null; state.display=null;
        if (state.choices.isEmpty()) {
            if (notices.shouldNotify(player.getUniqueId(),"empty:"+material.id)) plugin.messages().send(player,
                    material==ForgeMaterial.TEMPER||material==ForgeMaterial.INSCRIBE||material==ForgeMaterial.REMOVE
                            ?"forge-upgrade-invalid":"forge-property-invalid");
            return;
        }
        try {
            state.plan=plugin.materials().build(left,right,material,state.choices.get(state.index));
            state.display=state.plan.result().clone();
            var meta=state.display.getItemMeta();
            var lore=meta.lore()==null?new ArrayList<net.kyori.adventure.text.Component>():new ArrayList<>(meta.lore());
            String id=state.choices.get(state.index);
            String name=choiceName(material,id);
            lore.addAll(plugin.messages().getList("forge-anvil-preview","name",name,"index",state.index+1,"count",state.choices.size(),
                    "units",state.plan.units(),"xp",state.plan.xp()));
            meta.lore(lore); state.display.setItemMeta(meta);
            result.accept(state.display.clone());
            AnvilCosts.price(view,state.plan.xp()); view.setRepairItemCountCost(state.plan.units());
        } catch (ForgeMaterials.Failure failure) {
            if (notices.shouldNotify(player.getUniqueId(),failure.key+Arrays.toString(failure.arguments)))
                plugin.messages().send(player,failure.key,failure.arguments);
        }
    }
    private boolean current(Player player,AnvilView view,State state) {
        return states.get(player.getUniqueId())==state && player.getOpenInventory()==view && allowed(player,view)
                && state.plan!=null && state.plan.settings()==plugin.config() && state.inputs.matches(view,view.getTopInventory().getItem(0),view.getTopInventory().getItem(1),plugin.config(),view.getRenameText())
                && Objects.equals(state.display,view.getTopInventory().getItem(2))
                && AnvilCosts.actual(view)==state.plan.xp() && view.getRepairItemCountCost()==state.plan.units();
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void click(InventoryClickEvent event) {
        if (!(event.getView() instanceof AnvilView view) || event.getRawSlot()!=2 || !(event.getWhoClicked() instanceof Player player)) return;
        State state=states.get(player.getUniqueId());
        boolean materials=plugin.materials().kind(view.getTopInventory().getItem(0))!=null || plugin.materials().kind(view.getTopInventory().getItem(1))!=null;
        if (!materials && (state==null || state.inputs.view()!=view)) return;
        event.setCancelled(true);
        if (state==null || state.busy || !current(player,view,state)) return;
        boolean cycle=event.getClick()==ClickType.RIGHT;
        if (!cycle && (event.getClick()!=ClickType.LEFT || !event.getCursor().getType().isAir() || player.getLevel()<state.plan.xp())) {
            plugin.messages().send(player,"forge-anvil-take","xp",state.plan.xp()); return;
        }
        state.busy=true;
        // Ни выдачи, ни изменения слотов внутри click event. Поздняя задача повторно проверяет всю сцену.
        Bukkit.getScheduler().runTask(plugin,() -> {
            if (!current(player,view,state)) { state.busy=false; return; }
            if (cycle) {
                state.index=ForgeAnvilInputs.next(state.index,state.choices.size()); state.busy=false;
                render(player,view,item -> view.getTopInventory().setItem(2,item));
                if (state.display!=null) AnvilCosts.remember(view,state.display);
                player.updateInventory(); return;
            }
            if (!player.getItemOnCursor().getType().isAir() || player.getLevel()<state.plan.xp()) { state.busy=false; return; }
            var plan=state.plan;
            ItemStack rest=plan.right().clone(); rest.setAmount(rest.getAmount()-plan.units());
            states.remove(player.getUniqueId()); AnvilCosts.clear(view);
            var inventory=view.getTopInventory();
            int level=player.getLevel(), total=player.getTotalExperience(); float exp=player.getExp();
            try {
                inventory.setItem(2,null); inventory.setItem(0,null); inventory.setItem(1,rest.getAmount()==0?null:rest);
                player.giveExpLevels(-plan.xp()); player.setItemOnCursor(plan.result().clone()); view.setRepairCost(-1);
            } catch (RuntimeException failure) {
                player.setItemOnCursor(null); inventory.setItem(0,plan.left().clone()); inventory.setItem(1,plan.right().clone()); inventory.setItem(2,null);
                AnvilCosts.clear(view); view.setRepairCost(-1);
                player.setLevel(level); player.setTotalExperience(total); player.setExp(exp); player.updateInventory();
                plugin.getLogger().log(java.util.logging.Level.SEVERE,"Кузня: отменено незавершённое применение",failure);
                return;
            }
            player.updateInventory(); plugin.messages().send(player,"forge-done");
            plugin.collection().record(player,plan.result()); AnvilUse.finish(view,player);
        });
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void drag(InventoryDragEvent event) {
        if (event.getView() instanceof AnvilView && event.getRawSlots().contains(2)) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        State state=states.get(event.getPlayer().getUniqueId());
        if (state!=null && state.inputs.view()==event.getView()) states.remove(event.getPlayer().getUniqueId());
        notices.clear(event.getPlayer().getUniqueId());
    }
    @EventHandler public void quit(PlayerQuitEvent event) { states.remove(event.getPlayer().getUniqueId()); notices.clear(event.getPlayer().getUniqueId()); }
}
