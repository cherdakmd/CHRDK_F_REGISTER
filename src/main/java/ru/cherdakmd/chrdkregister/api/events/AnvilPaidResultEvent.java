package ru.cherdakmd.chrdkregister.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** API41: синхронное read-only уведомление ПОСЛЕ ручной оплаты/выдачи Core.
 * Не preview, не запрос на выдачу и не отменяемое событие. Обычный native take сюда не дублируется.
 * Все предметы возвращаются копиями; слушатель не должен повторять оплату или выдачу. */
public final class AnvilPaidResultEvent extends PlayerEvent {
    private static final HandlerList HANDLERS=new HandlerList();
    private final AnvilView view;
    private final ItemStack left,right,result;
    private final int levels;
    public AnvilPaidResultEvent(Player player,AnvilView view,ItemStack left,@Nullable ItemStack right,ItemStack result,int levels) {
        super(Objects.requireNonNull(player));
        if(levels<40) throw new IllegalArgumentException("Manual full-price take must cost at least 40 levels");
        this.view=Objects.requireNonNull(view);this.left=Objects.requireNonNull(left).clone();
        this.right=right==null?null:right.clone();this.result=Objects.requireNonNull(result).clone();this.levels=levels;
    }
    public AnvilView view() { return view; }
    public ItemStack left() { return left.clone(); }
    public @Nullable ItemStack right() { return right==null?null:right.clone(); }
    public ItemStack result() { return result.clone(); }
    public int levels() { return levels; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
