package com.fizik.glasspierce;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Метки урона за каждое пробитое стекло: config {@code damageMultiplierPerBreak}.
 *
 * <p>Официальный TaCZ API: {@link EntityHurtByGunEvent.Pre} постится из
 * {@code EntityKineticBullet#onHitEntity} (только LogicalSide.SERVER) с базовым уроном,
 * вычисленным по дистанции, — и TaCZ затем читает результат через
 * {@code preEvent.getBaseAmount()}. Поэтому меняем baseAmount напрямую:</p>
 *
 * <pre>{@code
 * damage *= pow(damageMultiplierPerBreak, стеколПробитоЭтойПулей)
 * }</pre>
 *
 * <p>Счётчик повреждённых стекол берём из persistent data пули ({@link #NBT_KEY} /
 * {@code glasspierce:brokenGlass}), куда его пишет {@link PierceLogic}.</p>
 *
 * <p>Событие срабатывает на каждом новом попадании в сущность той же пулей,
 * включая попадания ПОСЛЕ пробития стекла — так множитель применяется ровно
 * на нужное число раз и не "двойбится" между entity-hit'ами.</p>
 */
@Mod.EventBusSubscriber(modid = GlassPierceMod.MODID)
public final class GunDamageModifierListener {

    @SubscribeEvent
    public static void onHurtByGunPre(EntityHurtByGunEvent.Pre event) {
        if (!Config.extensionEnabled()) {
            return;
        }
        Entity bullet = event.getBullet();
        int broken = PierceLogic.brokenGlassCount(bullet);
        if (broken <= 0) {
            return; // стекло не пробивалось — урон не трогаем
        }
        double multiplier = Config.damageMultPerBreak();
        if (multiplier == 1.0D) {
            return;
        }
        float newAmount = (float) (event.getBaseAmount() * Math.pow(multiplier, broken));
        event.setBaseAmount(Math.max(newAmount, 0.0F));
    }

    private GunDamageModifierListener() {
    }
}
