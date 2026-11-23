package ru.cherdakmd.chrdkenchants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** Navigation only. Existing handlers retain all permissions, prices and confirmation rules. */
final class EnchantHub implements Listener {
    enum Screen { MAIN, GUARD, CLEANSE }
    record Button(int slot, Material icon, String key, String command, boolean handSensitive) { }
    private static final List<Button> MAIN = List.of(
            new Button(10,Material.ENCHANTED_BOOK,"catalog","enchants catalog",false),
            new Button(11,Material.BOOKSHELF,"library","enchants library",false),
            new Button(12,Material.WRITABLE_BOOK,"scribe","enchants scribe",true),
            new Button(13,Material.SPYGLASS,"inspect","enchants inspect",true),
            new Button(14,Material.IRON_SWORD,"hand","enchants hand",true),
            new Button(15,Material.EMERALD,"sources","enchants sources",false),
            new Button(16,Material.ANVIL,"anvil",null,false),
            new Button(19,Material.AMETHYST_SHARD,"cleanse",null,true),
            new Button(20,Material.ENCHANTING_TABLE,"combine","enchants combine",true),
            new Button(21,Material.SHIELD,"guard",null,false),
            new Button(22,Material.CLOCK,"cooldowns","enchants cooldowns",false),
            new Button(23,Material.BOOK,"books","enchants help books",false),
            new Button(24,Material.CRAFTING_TABLE,"workshop","enchants help workshop",false),
            new Button(25,Material.EXPERIENCE_BOTTLE,"effects","enchants help effects",false),
            new Button(28,Material.GLOWSTONE_DUST,"light","enchants light",false),
            new Button(29,Material.SOUL_LANTERN,"curses","enchants curses",false),
            new Button(30,Material.COMPASS,"search",null,false),
            new Button(31,Material.PAPER,"help","enchants help",false),
            new Button(32,Material.COMPARATOR,"guard-status","enchants guard",false));
    private static final Map<Integer,String> GUARD = Map.of(10,"auto",11,"briar_guard",12,"faltering_guard",13,"signal_guard",14,"pve_anchor");
    private final EnchantsPlugin plugin;
    EnchantHub(EnchantsPlugin plugin) { this.plugin=plugin; }
    static List<Button> buttons() { return MAIN; }
    private static final class Holder implements InventoryHolder {
        final UUID owner; final long generation; final Screen screen; final int heldSlot;
        final ItemStack hand,off; final Map<Integer,String> choices; Inventory inventory;
        Holder(Player player,long generation,Screen screen,Map<Integer,String> choices) {
            owner=player.getUniqueId();this.generation=generation;this.screen=screen;
            heldSlot=player.getInventory().getHeldItemSlot();hand=player.getInventory().getItemInMainHand().clone();
            off=player.getInventory().getItemInOffHand().clone();this.choices=Map.copyOf(choices);
        }
        @Override public @NotNull Inventory getInventory(){return inventory;}
    }
    private boolean eligible(Player player) {
        return plugin.isEnabled() && player.isOnline() && !player.isDead() && plugin.coreApi()!=null
                && plugin.coreApi().isAuthenticated(player) && player.hasPermission("chrdkenchants.use");
    }
    void open(Player player) { if(eligible(player)) show(player,Screen.MAIN,Map.of()); }
    private void show(Player player,Screen screen,Map<Integer,String> choices) {
        var holder=new Holder(player,plugin.sourceGeneration(),screen,choices);
        Inventory inv=Bukkit.createInventory(holder,54,plugin.messages().get(switch(screen){
            case MAIN -> "hub-title"; case GUARD -> "hub-guard-title"; case CLEANSE -> "hub-cleanse-title"; }));
        holder.inventory=inv;
        if(screen==Screen.MAIN) {
            for(Button button:MAIN)inv.setItem(button.slot(),icon(button.icon(),plugin.messages().get("hub-"+button.key()),
                    plugin.messages().get("hub-"+button.key()+"-lore")));
            if(player.hasPermission("chrdkenchants.admin"))inv.setItem(34,icon(Material.COMMAND_BLOCK,
                    plugin.messages().get("hub-admin"),plugin.messages().get("hub-admin-lore")));
        } else if(screen==Screen.GUARD) {
            GUARD.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> inv.setItem(e.getKey(),
                    icon(Material.SHIELD,e.getValue().equals("auto")?plugin.messages().get("guard-auto")
                            :plugin.messages().get("en."+e.getValue()+".name"),plugin.messages().get("hub-guard-choice"))));
        } else {
            choices.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->inv.setItem(e.getKey(),
                    icon(Material.SOUL_LANTERN,plugin.messages().get("en."+e.getValue()+".name"),plugin.messages().get("hub-cleanse-choice"))));
        }
        inv.setItem(49,icon(Material.BARRIER,plugin.messages().get(screen==Screen.MAIN?"hub-close":"hub-back")));
        player.openInventory(inv);
    }
    private ItemStack icon(Material type,Component title,Component... lore) {
        ItemStack stack=new ItemStack(type);var meta=stack.getItemMeta();meta.displayName(title);meta.lore(List.of(lore));stack.setItemMeta(meta);return stack;
    }
    private boolean current(Player player,Holder holder,boolean hand) {
        return eligible(player) && holder.owner.equals(player.getUniqueId()) && holder.generation==plugin.sourceGeneration()
                && player.getOpenInventory().getTopInventory().getHolder()==holder
                && (!hand || holder.heldSlot==player.getInventory().getHeldItemSlot()
                    && Objects.equals(holder.hand,player.getInventory().getItemInMainHand())
                    && Objects.equals(holder.off,player.getInventory().getItemInOffHand()));
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        boolean blocked=event.isCancelled();event.setCancelled(true);
        if(blocked || !(event.getWhoClicked() instanceof Player player) || !current(player,holder,false)
                || (event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT))return;
        int slot=event.getRawSlot();
        if(slot==49){ schedule(player,holder,false,()->{if(holder.screen==Screen.MAIN)player.closeInventory();else show(player,Screen.MAIN,Map.of());});return; }
        if(holder.screen==Screen.GUARD) {
            String choice=GUARD.get(slot);if(choice!=null)command(player,holder,"enchants guard "+choice,false);
            return;
        }
        if(holder.screen==Screen.CLEANSE) {
            String choice=holder.choices.get(slot);if(choice!=null)command(player,holder,"enchants cleanse "+choice,true);
            return;
        }
        if(slot==34 && player.hasPermission("chrdkenchants.admin")){command(player,holder,"enchantsadmin",false);return;}
        Button button=MAIN.stream().filter(b->b.slot()==slot).findFirst().orElse(null);if(button==null)return;
        if(button.command()!=null){command(player,holder,button.command(),button.handSensitive());return;}
        switch(button.key()) {
            case "guard" -> schedule(player,holder,false,()->show(player,Screen.GUARD,Map.of()));
            case "cleanse" -> schedule(player,holder,true,()->openCleanse(player));
            case "anvil" -> schedule(player,holder,false,()->{
                player.closeInventory();plugin.messages().sendList(player,"help-anvil");
                player.sendMessage(plugin.messages().get("hub-anvil-instruction"));
            });
            case "search" -> schedule(player,holder,false,()->{
                player.closeInventory();player.sendMessage(plugin.messages().get("hub-search-prompt")
                        .clickEvent(ClickEvent.suggestCommand("/enchants find ")));
            });
            default -> { }
        }
    }
    private void openCleanse(Player player) {
        var data=plugin.items().validated(player.getInventory().getItemInMainHand());
        if(data==null) {player.closeInventory();plugin.messages().send(player,"workshop-rejected");return;}
        var choices=new LinkedHashMap<Integer,String>();
        data.keySet().stream().sorted().filter(id->{var def=EnchantRegistry.byId(id);return def!=null && def.curse();})
                .limit(45).forEach(id->choices.put(choices.size(),id));
        if(choices.isEmpty()){player.closeInventory();plugin.messages().send(player,"hub-cleanse-empty");return;}
        show(player,Screen.CLEANSE,choices);
    }
    private void command(Player player,Holder holder,String command,boolean hand) {
        schedule(player,holder,hand,()->{
            player.closeInventory();
            String[] parts=command.split(" ");
            // Resolve only this plugin's own command, not another plugin's alias.
            var bound=plugin.getCommand(parts[0]);
            if(bound!=null)bound.execute(player,parts[0],Arrays.copyOfRange(parts,1,parts.length));
        });
    }
    private void schedule(Player player,Holder holder,boolean hand,Runnable action) {
        Bukkit.getScheduler().runTask(plugin,()->{if(current(player,holder,hand))action.run();});
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    void closeAll() {for(Player player:Bukkit.getOnlinePlayers())if(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)player.closeInventory();}
}
