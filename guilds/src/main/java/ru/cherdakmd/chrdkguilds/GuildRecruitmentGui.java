package ru.cherdakmd.chrdkguilds;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** Список и одноразовое согласие: просмотр не меняет приглашения и никогда не списывает деньги. */
final class GuildRecruitmentGui implements Listener {
    static final int[] SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    static final long CONSENT_MILLIS = 30_000;
    static final class Holder implements InventoryHolder {
        final UUID owner;
        final int page;
        final GuildJoinTerms terms;
        final long expires;
        final Map<Integer,String> targets = new HashMap<>();
        boolean used;
        Inventory inventory;
        Holder(UUID owner,int page,GuildJoinTerms terms,long expires) { this.owner=owner;this.page=page;this.terms=terms;this.expires=expires; }
        @Override public Inventory getInventory() { return inventory; }
        boolean consume(long now) { if (used || terms == null) return false; used=true; return now < expires; }
    }
    private final GuildsPlugin plugin;
    GuildRecruitmentGui(GuildsPlugin plugin) { this.plugin=plugin; }
    private boolean allowed(Player player) {
        return player != null && player.isOnline() && !player.isDead() && !player.hasMetadata("NPC") && plugin.visible(player) && player.hasPermission("chrdkguilds.use")
                && plugin.config().enabled() && !plugin.payments().paused();
    }
    static List<Guild> ordered(List<Guild> guilds) {
        return guilds.stream().sorted(Comparator.comparing(Guild::recruitmentOpen).reversed()
                .thenComparing(Guild::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Guild::id)).toList();
    }
    void browse(Player player,int page) {
        if (!allowed(player)) { if (player != null) plugin.messages().send(player,"finance-paused"); return; }
        List<Guild> guilds=ordered(plugin.store().guilds());
        int pages=Math.max(1,(guilds.size()+SLOTS.length-1)/SLOTS.length);
        int actual=Math.clamp(page,0,pages-1);
        Holder h=new Holder(player.getUniqueId(),actual,null,0);
        h.inventory=Bukkit.createInventory(h,54,plugin.messages().get("recruit-list-title"));
        h.inventory.setItem(4,item(Material.BOOK,"recruit-list-heading",List.of(
                plugin.messages().get("recruit-list-page","page",actual+1,"pages",pages,"total",guilds.size()),
                plugin.messages().get("recruit-list-hint"))));
        if (guilds.isEmpty()) h.inventory.setItem(22,item(Material.PAPER,"recruit-empty",List.of()));
        long now=System.currentTimeMillis();
        GuildInvite invite=plugin.store().inviteOf(player.getUniqueId());
        for (int i=0;i<SLOTS.length && actual*SLOTS.length+i<guilds.size();i++) {
            Guild guild=guilds.get(actual*SLOTS.length+i);
            boolean invited=invite!=null && !invite.expired(now) && invite.guildId().equals(guild.id());
            List<Component> lore=new ArrayList<>();
            lore.add(plugin.messages().get("recruit-list-members","count",guild.size(),"cap",GuildMath.memberCap(guild.level()),"level",GuildMath.roman(guild.level())));
            lore.add(plugin.messages().get("recruit-list-mode","mode",guild.mode().label()));
            lore.add(plugin.messages().get("dues-invite","rate",guild.duesRate(),"peace",guild.peaceful()?GuildPeaceService.DAILY:0));
            lore.add(plugin.messages().get(guild.recruitmentOpen()?"recruit-open-hint":"recruit-closed"));
            if(invited)lore.add(plugin.messages().get("recruit-invited"));
            ItemStack entry=item(guild.recruitmentOpen()?Material.LIME_BANNER:Material.GRAY_BANNER,"recruit-entry",lore,"name",guild.name(),"tag",guild.tag());
            h.inventory.setItem(SLOTS[i],entry);h.targets.put(SLOTS[i],guild.id());
        }
        h.inventory.setItem(45,item(Material.ARROW,"recruit-back",List.of()));
        h.inventory.setItem(49,item(Material.OAK_DOOR,"recruit-cancel",List.of()));
        if(actual>0)h.inventory.setItem(48,item(Material.ARROW,"recruit-previous",List.of()));
        if(actual+1<pages)h.inventory.setItem(50,item(Material.ARROW,"recruit-next",List.of()));
        player.openInventory(h.inventory);
    }
    void invitation(Player player) {
        if (!allowed(player)) return;
        GuildInvite invite=plugin.store().inviteOf(player.getUniqueId());
        if (invite==null) { plugin.messages().send(player,"invite-none"); return; }
        if (invite.expired(System.currentTimeMillis())) { plugin.messages().send(player,"invite-expired"); return; }
        confirm(player,invite.guildId());
    }
    private void confirm(Player player,String guildId) {
        if (!allowed(player)) return;
        long now=System.currentTimeMillis();
        GuildJoinTerms terms=GuildJoinTerms.capture(plugin.store(),player.getUniqueId(),guildId,now);
        if (terms==null) { plugin.messages().send(player,"recruit-closed"); return; }
        var preview=GuildFinance.preview(plugin.store(),plugin.config(),(service,purse)->terms.invitation()==null
                ?service.joinOpen(purse,player.getUniqueId(),guildId,now):service.accept(purse,player.getUniqueId(),now));
        if (!preview.result().ok()) { plugin.messages().send(player,preview.result().key(),preview.result().array()); return; }
        if (preview.debit()!=terms.fee()) { plugin.messages().send(player,"recruit-changed"); return; }
        Holder h=new Holder(player.getUniqueId(),0,terms,now+CONSENT_MILLIS);
        h.inventory=Bukkit.createInventory(h,54,plugin.messages().get("recruit-confirm-title"));
        List<Component> lore=new ArrayList<>();
        lore.add(plugin.messages().get(terms.open()?"recruit-open-button":"recruit-closed-button"));
        lore.add(plugin.messages().get("recruit-list-mode","mode",terms.mode().label()));
        lore.add(plugin.messages().get("recruit-fees","rate",terms.dues(),"peace",terms.mode()==GuildMode.PEACEFUL?GuildPeaceService.DAILY:0,"cost",terms.fee()));
        lore.add(plugin.messages().get("recruit-daily"));
        lore.add(plugin.messages().get("recruit-nonpayment"));
        lore.add(plugin.messages().get("recruit-leader-risk"));
        lore.add(plugin.messages().get("recruit-pay-now","cost",terms.fee()));
        if (terms.mode()==GuildMode.PEACEFUL) {
            lore.add(plugin.messages().get("recruit-peace-warmup"));
            lore.add(plugin.messages().get("recruit-peace-fine"));
            lore.add(plugin.messages().get("recruit-peace-brand"));
            lore.add(plugin.messages().get("recruit-peace-head"));
        }
        lore.add(plugin.messages().get("recruit-consent-time"));
        h.inventory.setItem(13,item(Material.WHITE_BANNER,"recruit-entry",lore,"name",terms.name(),"tag",terms.tag()));
        h.inventory.setItem(30,item(Material.LIME_CONCRETE,"recruit-confirm-button",List.of(plugin.messages().get("recruit-pay-now","cost",terms.fee()))));
        h.inventory.setItem(32,item(Material.RED_CONCRETE,"recruit-cancel",List.of()));
        player.openInventory(h.inventory);
    }
    void setting(Player player,String[] args) {
        if (!allowed(player)) return;
        if (args.length!=2 || !(args[1].equalsIgnoreCase("open")||args[1].equalsIgnoreCase("closed"))) {
            plugin.messages().send(player,"recruit-setting-help");return;
        }
        change(player,args[1].equalsIgnoreCase("open"));
    }
    void toggle(Player player) {
        if (!allowed(player)) return;
        Guild guild=plugin.store().of(player.getUniqueId());
        if(guild!=null)change(player,!guild.recruitmentOpen());
    }
    private void change(Player player,boolean open) {
        GuildResult result=plugin.service().recruitment(player.getUniqueId(),open);
        plugin.messages().send(player,result.key(),result.array());
        if(result.ok()){plugin.requestSave();plugin.gui().refresh();}
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !h.owner.equals(player.getUniqueId()) || !allowed(player)) return;
        if (event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT) return;
        int slot=event.getRawSlot();if(slot<0||slot>=54)return;
        String target=h.targets.get(slot);
        if(h.terms!=null && slot==30 && !h.consume(System.currentTimeMillis())) { plugin.messages().send(player,"recruit-changed");return; }
        Bukkit.getScheduler().runTask(plugin,()->{
            if (!allowed(player) || player.getOpenInventory().getTopInventory().getHolder()!=h) return;
            if(h.terms!=null) {
                if(slot==32){player.closeInventory();return;}
                if(slot!=30)return;
                // Один финансовый preview с повторной проверкой целевых условий → общий банковский WAL.
                player.closeInventory();
                GuildResult result=plugin.finance().join(player,h.terms,h.expires,System.currentTimeMillis());
                plugin.messages().send(player,result.key(),result.array());
                if(result.ok()) {
                    plugin.requestSave();
                    Guild guild=plugin.store().of(player.getUniqueId());
                    if(guild!=null)for(UUID id:guild.memberIds()) {
                        Player member=Bukkit.getPlayer(id);
                        if(plugin.visible(member))plugin.messages().send(member,"join-broadcast","player",player.getName());
                    }
                    plugin.gui().open(player);
                }
                return;
            }
            if(slot==49){player.closeInventory();return;}
            if(slot==45){plugin.gui().open(player);return;}
            if(slot==48&&h.page>0){browse(player,h.page-1);return;}
            if(slot==50&&h.inventory.getItem(50)!=null){browse(player,h.page+1);return;}
            if(target!=null)confirm(player,target);
        });
    }
    void closeAll() {
        for (Player player:Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder h) {
                h.used=true; player.closeInventory();
            }
        }
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
    private ItemStack item(Material type,String key,List<Component> lore,Object... placeholders) {
        ItemStack result=new ItemStack(type);var meta=result.getItemMeta();
        meta.displayName(plugin.messages().get(key,placeholders));meta.lore(lore);result.setItemMeta(meta);return result;
    }
}
