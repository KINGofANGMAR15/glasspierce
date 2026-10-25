package com.fizik.glasspierce;

import com.fizik.glasspierce.Config;
import com.fizik.glasspierce.GlassPierceMod;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <b>Вариант B (основной, по умолчанию):</b> слушатель отменяемого события TaCZ
 * {@code com.tacz.guns.api.event.server.AmmoHitBlockEvent}.
 *
 * <h2>Почему вариант B выбран основным</h2>
 * <ul>
 *   <li>Не привязан к внутренностям TaCZ: опирается только на публичное API —
 *   {@link AmmoHitBlockEvent} (level, hitResult, state, {@code getAmmo(): EntityKineticBullet}).
 *   Подходит для обновлений TaCZ без правки Mixin/refmap.</li>
 *   <li>Механика ограничения выяснена по исходникам: ограничение "1 стекло" в TaCZ — это
 *   <i>отсутствие отмены</i> события для стекла: стоковый слушатель
 *   {@code com.tacz.guns.event.ammo.DestroyGlassBlock} только ломает блок, но не отменяет
 *   событие, и потому {@code EntityKineticBullet#onHitBlock(...)} доходит до
 *   {@code this.discard()} — пуля гаснет на первом стекле. "Продолжить полёт" = просто
 *   отменить событие (см. PierceLogic, класс-док).</li>
 *   <li>Приоритет {@link EventPriority#HIGHEST} нужен, чтобы выполняться ДО штатного
 *   слушателя стекла TaCZ (у него NORMAL и default receiveCanceled=false): отмена события
 *   с HIGHEST-приоритета отключает его, и мы вызываем ровно тот же
 *   {@code level.destroyBlock(pos, false, owner)} — штатные звук и частицы сохраняются,
 *   двойного разрушения не происходит.</li>
 * </ul>
 *
 * <h2>Почему это НЕ "дублирование" эффектов</h2>
 * <p>Стоковый слушатель TaCZ сводится к одному вызову {@code Level.destroyBlock}; звук/частицы
 * рождает не слушатель, а серверная система (levelEvent 2001). Мы вызываем ту же точку входа —
 * эффект бит-в-бит стоковый, дублирования нет (слушатель от нас получает уже отменённое событие
 * и не срабатывает).</p>
 */
@Mod.EventBusSubscriber(modid = GlassPierceMod.MODID)
public final class AmmoHitBlockListener {

    // Приоритет задаётся в @SubscribeEvent (у @Mod.EventBusSubscriber нет атрибута priority):
    // HIGHEST нужен, чтобы выполниться ДО стокового слушателя DestroyGlassBlock (NORMAL).
    private static boolean loggedFirstHit = false;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (!loggedFirstHit) {
            loggedFirstHit = true;
            GlassPierceMod.LOGGER.info(
                    "glasspierce: слушатель AmmoHitBlockEvent жив, mode={}; блок={} ({}), отменено другими={}",
                    Config.mode(),
                    BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()),
                    event.getState(),
                    event.isCanceled());
        }
        // MIXIN-режим: слушатель полностью в стороне, работает EntityKineticBulletMixin.
        if (Config.mode() != Config.Mode.EVENT || !Config.extensionEnabled()) {
            return;
        }
        // Если событие уже отменил кто-то другой — не спорим и не вмешиваемся.
        if (event.isCanceled()) {
            return;
        }

        Level level = event.getLevel();
        BlockState state = event.getState();
        EntityKineticBullet ammo = event.getAmmo();

        if (PierceLogic.processGlassHit(level, event.getHitResult(), state, ammo)) {
            // Отмена => EntityKineticBullet#onHitBlock завершится ранним return:
            // не будет super.onHitBlock, частиц следа и discard — пуля летит дальше.
            event.setCanceled(true);
        }
    }

    private AmmoHitBlockListener() {
    }
}
