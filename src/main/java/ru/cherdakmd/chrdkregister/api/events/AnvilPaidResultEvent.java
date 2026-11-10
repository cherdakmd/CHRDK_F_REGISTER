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
 * Все предметы возвращаются копиями; слушатель не должен повторять оплату или выдачу.
 *
 * <p>Оплата бывает двух видов: полная цена уровнями ({@link #levels()} &gt;= 40,
 * {@link #diamonds()} == 0) либо алмазы с {@code /bal} для операции дороже
 * потолка ({@link #levels()} == 0, {@link #diamonds()} &gt; 0). Второй
 * конструктор добавлен позже и не меняет контракт первого.</p> */
public final class AnvilPaidResultEvent extends PlayerEvent {
    private static final HandlerList HANDLERS=new HandlerList();
    private final AnvilView view;
    private final ItemStack left,right,result;
    private final int levels;
    private final long diamonds;

    /** Ручная оплата полной цены уровнями опыта. */
    public AnvilPaidResultEvent(Player player,AnvilView view,ItemStack left,@Nullable ItemStack right,ItemStack result,int levels) {
        this(player,view,left,right,result,levels,0L);
    }

    /** Ручная оплата: ровно один из способов — уровни (&gt;= 40) или алмазы (&gt; 0). */
    public AnvilPaidResultEvent(Player player,AnvilView view,ItemStack left,@Nullable ItemStack right,ItemStack result,
                                int levels,long diamonds) {
        super(Objects.requireNonNull(player));
        if(levels<40 && diamonds<=0L) throw new IllegalArgumentException("Manual full-price take must cost at least 40 levels or diamonds");
        if(levels>0 && diamonds>0L) throw new IllegalArgumentException("Manual take is paid either in levels or in diamonds");
        if(diamonds<0L) throw new IllegalArgumentException("Diamond price cannot be negative");
        this.view=Objects.requireNonNull(view);this.left=Objects.requireNonNull(left).clone();
        this.right=right==null?null:right.clone();this.result=Objects.requireNonNull(result).clone();
        this.levels=levels;this.diamonds=diamonds;
    }
    public AnvilView view() { return view; }
    public ItemStack left() { return left.clone(); }
    public @Nullable ItemStack right() { return right==null?null:right.clone(); }
    public ItemStack result() { return result.clone(); }
    /** Полная цена в уровнях XP; {@code 0} для валютной оплаты. */
    public int levels() { return levels; }
    /** Число алмазов, списанных с {@code /bal}; {@code 0} для оплаты уровнями. */
    public long diamonds() { return diamonds; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
