package ru.cherdakmd.chrdkguilds;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Temporary party menus delegate all mutations to the existing checked command path. */
final class AdventureGroupGui implements Listener {
    private static final int SIZE=54, PAGE_SIZE=28;
    private final GuildsPlugin plugin;
    AdventureGroupGui(GuildsPlugin plugin) { this.plugin=plugin; }
    private static final class Holder implements InventoryHolder {
        final UUID owner; final boolean invites; final int page; final Map<Integer,UUID> targets=new HashMap<>(); Inventory inventory;
        Holder(UUID owner,boolean invites,int page) {this.owner=owner;this.invites=invites;this.page=page;}
        @Override public Inventory getInventory() { return inventory; }
    }
    static int memberSlot(int index) {
        if(index<0 || index>=16) throw new IllegalArgumentException("invalid member index");
        return 10+index+(index/8);
    }
    static int invitationSlot(int index) {
        if(index<0 || index>=PAGE_SIZE) throw new IllegalArgumentException("invalid invitation index");
        return 10+(index/7)*9+(index%7);
    }
    private boolean allowed(Player p) {
        return p!=null && p.isOnline() && !p.isDead() && !p.hasMetadata("NPC") && plugin.visible(p)
                && plugin.payments()!=null && !plugin.payments().paused();
    }
    void open(Player p) {
        if (!allowed(p)) return;
        var h=new Holder(p.getUniqueId(),false,0);
        h.inventory=Bukkit.createInventory(h,SIZE,Component.text("Пати"));
        List<UUID> members=plugin.adventureGroups().menuMembers(p.getUniqueId());
        h.inventory.setItem(4,control(Material.BOOK,"Пати",members.isEmpty()?"Вы пока не состоите в подтверждённой группе.":"Участников: "+members.size(),members.isEmpty()?GuildPartyVisuals.Control.EMPTY:GuildPartyVisuals.Control.GROUP));
        for (int i=0;i<members.size() && i<16;i++) {
            Player member=Bukkit.getPlayer(members.get(i));
            if(member!=null && p.canSee(member)) h.inventory.setItem(memberSlot(i),icon(Material.PLAYER_HEAD,member.getName(),i==0?"Лидер пати":"Участник пати"));
        }
        h.inventory.setItem(37,control(Material.LIME_DYE,"Пригласить игрока","Выбрать игрока онлайн",GuildPartyVisuals.Control.INVITE));
        h.inventory.setItem(39,control(Material.LIME_CONCRETE,"Принять приглашение","Приглашение действует 60 секунд",GuildPartyVisuals.Control.ACCEPT));
        h.inventory.setItem(41,control(Material.RED_CONCRETE,"Отклонить приглашение","Отклонить последнее приглашение",GuildPartyVisuals.Control.DECLINE));
        h.inventory.setItem(43,control(Material.OAK_DOOR,"Покинуть пати","Лидер распускает группу при выходе",GuildPartyVisuals.Control.LEAVE));
        h.inventory.setItem(49,control(Material.BARRIER,"Закрыть","",GuildPartyVisuals.Control.CLOSE));
        p.openInventory(h.inventory);
    }
    private void invitations(Player p,int page) {
        if(!allowed(p))return;
        List<Player> candidates=Bukkit.getOnlinePlayers().stream().filter(other -> !other.equals(p)
                && !other.hasMetadata("NPC") && p.canSee(other) && plugin.visible(other))
                .sorted(Comparator.comparing(Player::getName,String.CASE_INSENSITIVE_ORDER)).map(x -> (Player)x).toList();
        int pages=Math.max(1,(candidates.size()+PAGE_SIZE-1)/PAGE_SIZE);
        int actual=Math.clamp(page,0,pages-1);
        var h=new Holder(p.getUniqueId(),true,actual);
        h.inventory=Bukkit.createInventory(h,SIZE,Component.text("Пригласить · "+(actual+1)+"/"+pages));
        for(int i=0;i<PAGE_SIZE && actual*PAGE_SIZE+i<candidates.size();i++) {
            Player other=candidates.get(actual*PAGE_SIZE+i);
            int slot=invitationSlot(i);
            h.inventory.setItem(slot,icon(Material.PLAYER_HEAD,other.getName(),"Отправить приглашение на 60 секунд"));
            h.targets.put(slot,other.getUniqueId());
        }
        if(actual>0)h.inventory.setItem(45,control(Material.ARROW,"Назад","Предыдущая страница",GuildPartyVisuals.Control.PREV));
        h.inventory.setItem(49,control(Material.OAK_DOOR,"Вернуться","Меню пати",GuildPartyVisuals.Control.BACK));
        if(actual+1<pages)h.inventory.setItem(53,control(Material.ARROW,"Далее","Следующая страница",GuildPartyVisuals.Control.NEXT));
        p.openInventory(h.inventory);
    }
    void closeAll() {
        for(Player p:Bukkit.getOnlinePlayers())
            if(p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
    }
    private static ItemStack icon(Material mat,String name,String lore) {
        var item=new ItemStack(mat);var meta=item.getItemMeta();meta.displayName(Component.text(name));
        if(!lore.isEmpty())meta.lore(List.of(Component.text(lore)));item.setItemMeta(meta);return item;
    }
    private ItemStack control(Material mat,String name,String lore,GuildPartyVisuals.Control control) {
        ItemStack item=icon(mat,name,lore);
        if (plugin.getConfig().getBoolean("textures.party-menu",false)) {
            var meta=item.getItemMeta(); GuildPartyVisuals.apply(meta,control,true);
            if (meta!=null) item.setItemMeta(meta);
        }
        return item;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;
        boolean alreadyCancelled=event.isCancelled();
        event.setCancelled(true);
        if(alreadyCancelled)return;
        if(!(event.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || !allowed(p))return;
        if(event.getClick()!=org.bukkit.event.inventory.ClickType.LEFT && event.getClick()!=org.bukkit.event.inventory.ClickType.RIGHT)return;
        int slot=event.getRawSlot(); if(slot<0 || slot>=SIZE)return;
        UUID target=h.targets.get(slot);
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!allowed(p) || p.getOpenInventory().getTopInventory().getHolder()!=h)return;
            if(h.invites) {
                if(slot==49) {open(p);return;}
                if(slot==45) {invitations(p,h.page-1);return;}
                if(slot==53) {invitations(p,h.page+1);return;}
                if(target==null)return;
                Player other=Bukkit.getPlayer(target);
                if(other==null || !p.canSee(other) || !plugin.visible(other)) {p.sendMessage(Component.text("Игрок больше недоступен."));open(p);return;}
                plugin.adventureGroups().command(p,new String[]{"squad","invite",other.getName()});
                open(p);return;
            }
            if(slot==49) {p.closeInventory();return;}
            if(slot==37) {invitations(p,0);return;}
            String action=switch(slot) {case 39->"accept";case 41->"decline";case 43->"leave";default->null;};
            if(action!=null) {plugin.adventureGroups().command(p,new String[]{"squad",action});open(p);}
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true); }
}
