package ru.cherdakmd.chrdktierforge;

import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Чистая логика preview-сцены Кузни в наковальне.
 *
 * <p>Ядро на HIGHEST дообъединяет атрибуты результата уже после нашего HIGH-превью
 * (см. {@code ItemAttributeMergeListener}): восстановленная база Mine Treasure делает
 * итоговый стек другим, чем собранный планом display. Раньше строгая проверка
 * {@code display == слот[2]} в {@code current()} из-за этого молча запрещала забор
 * предмета («невозможно забрать из наковальни»). Display обязан догонять объединённый
 * стек, а выдача — ровно показанное изменение без служебного preview-лора.</p>
 */
final class ForgeAnvilPreview {
    private ForgeAnvilPreview() { }

    /** true — результат переписан после HIGH-превью; display обязан догнать объединённый стек. */
    static boolean stale(Object display,Object merged) {
        return merged!=null&&!Objects.equals(display,merged);
    }

    /** Служебный хвост preview-лора (ровно previewLines строк) убирается из лора выдачи. */
    static <T> List<T> strip(List<T> lore,int previewLines) {
        if(lore==null||previewLines<=0) return lore==null?null:List.copyOf(lore);
        if(lore.size()<=previewLines) return List.of();
        return List.copyOf(lore.subList(0,lore.size()-previewLines));
    }

    /** Выдача: ровно показанное изменение, без строк «Выбрано/Цена/ПКМ…». */
    static ItemStack give(ItemStack shown,int previewLines) {
        if(shown==null) return null;
        ItemStack out=shown.clone();
        var meta=out.getItemMeta();
        if(meta!=null) {
            meta.lore(strip(new ArrayList<>(meta.lore()==null?List.of():meta.lore()),previewLines));
            out.setItemMeta(meta);
        }
        return out;
    }
}
