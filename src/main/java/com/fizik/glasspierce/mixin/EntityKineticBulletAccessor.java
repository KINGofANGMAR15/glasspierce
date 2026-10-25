package com.fizik.glasspierce.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Чтение приватных свойств пули TaCZ, которые нужны правилам лимита пробития:
 * <ul>
 *   <li>{@code explosion} — «взрывчатый» боеприпас (RPG-7, 40mm, фугасные улучшения HE):
 *   такие боеприпасы по умолчанию НЕ пробивают стекло штатной механикой — они должны
 *   взрываться (см. конфиг allowExplosivePiercing, по умолчанию false).</li>
 *   <li>{@code pierce} — TaCZ-модификатор «пробитие сущностей» (повышается улучшениями
 *   типа FMJ); используется как опциональный бонус к лимиту стекла (pierceBonusMax).</li>
 * </ul>
 *
 * <p>У TaCZ нет публичных геттеров этих полей (проверено по 1.1.8-hotfix2, в исходниках
 * ветки 1.20.1), поэтому нужен Accessor. Miksin только читает значение и не меняет
 * логику TaCZ. Если TaCZ переименует поля, mixin не применится; на этот случай вызовы
 * в PierceLogic обёрнуты в защитный try/catch — мод продолжит работать со стоковым
 * ограничением лимита.</p>
 */
@Mixin(value = EntityKineticBullet.class, remap = false)
public interface EntityKineticBulletAccessor {

    /** {@code private boolean explosion} (EntityKineticBullet.java, строка ~123). */
    @Accessor("explosion")
    boolean glasspierce$isExplosive();

    /** {@code private int pierce} (по умолчанию 1; повышается улучшениями типа FMJ). */
    @Accessor("pierce")
    int glasspierce$pierce();
}
