package com.fizik.glasspierce.mixin;

import com.fizik.glasspierce.Config;
import com.fizik.glasspierce.GlassPierceMod;
import com.fizik.glasspierce.PierceLogic;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * <b>Вариант A (альтернативный, включается конфигом mode=MIXIN).</b>
 *
 * <h2>Как это работает</h2>
 * <p>Единственное место в TaCZ, где решается судьба пули при попадании в блок —
 * {@code com.tacz.guns.entity.EntityKineticBullet#onHitBlock(BlockHitResult, Vec3, Vec3)}
 * (ветка 1.20.1, строка ~478):</p>
 * <pre>
 *     // оригинальный комментарий TaCZ: событие постится заранее, чтобы его отменой
 *     // можно было отключить штатные реакции попадания (колокол, мишени и т.п.)
 *     if (MinecraftForge.EVENT_BUS.post(new AmmoHitBlockEvent(...))) {
 *         return;                    // событие отменено: пуля ЖИВА и летит дальше
 *     }
 *     super.onHitBlock(result);
 *     ... BulletHoleOption - частицы следа ...
 *     this.discard();                // событие не отменено: пуля ГИБНЕТ на этом блоке
 * </pre>
 * <p>Мы перехватываем сам вызов {@code IEventBus.post(...)} ({@code @Redirect}) и получаем
 * возможность вернуть "true" ("отменено") вызывающему коду в нужных нам случаях —
 * это включает штатный ранний выход {@code return} без {@code discard()}.</p>
 *
 * <h2>Почему в MIXIN-режиме штатные звук/частицы сохраняются сами</h2>
 * <p>При пробитии мы ПОСТИМ реальное событие через оригинальный {@code bus.post(hit)}:
 * стоковый слушатель {@code com.tacz.guns.event.ammo.DestroyGlassBlock} сам вызывает
 * {@code level.destroyBlock(pos, false, ammo.getOwner())} — звук и частицы разрушения
 * рождаются штатной серверной системой (levelEvent 2001), мы их вообще не эмулируем.
 * От "не отменено" возвращаем к вызывающему коду только результат "true", чтобы пропустить
 * участок {@code super.onHitBlock + частицы следа + discard} (пуля продолжает полёт).</p>
 *
 * <h2>Безопасность совместимости</h2>
 * <ul>
 *   <li>{@code require = 0, expect = 0}: если TaCZ обновится и код сместится, миксин молча
 *   НЕ применится — мод не крашнет игру, продолжит работать как сток (в режиме EVENT
 *   слушатель {@code AmmoHitBlockListener} продолжает работать независимо от этого класса).</li>
 *   <li>{@code remap = false}: собственные имена TaCZ (классы/методы) не обфусцированы
 *   и не ремапятся; Forge-классы (IEventBus#post) тоже. Refmap для этого хука почти пуст.</li>
 *   <li>Целевая сигнатура:
 *   {@code onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V}.</li>
 * </ul>
 *
 * <h2>Где найти точку, если TaCZ обновится</h2>
 * <ol>
 *   <li>Исходники: github.com/MCModderAnchor/TACZ, ветка <b>1.20.1</b>.</li>
 *   <li>{@code src/main/java/com/tacz/guns/entity/EntityKineticBullet.java}: метод
 *   {@code onHitBlock(...)}; слушатель стекла:
 *   {@code src/main/java/com/tacz/guns/event/ammo/DestroyGlassBlock.java}.</li>
 *   <li>Или декомпилировать jar (Vineflower/CFR) и искать строку
 *   {@code new AmmoHitBlockEvent(} / {@code MinecraftForge.EVENT_BUS.post}.</li>
 * </ol>
 */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class EntityKineticBulletMixin {

    /** Однократный лог подтверждения: хук применился и реально вызывается. */
    @Unique
    private static boolean glasspierce$loggedActive = false;

    @Redirect(
            method = "onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    // MinecraftForge.EVENT_BUS имеет тип IEventBus; метод post(Event) возвращает boolean.
                    target = "Lnet/minecraftforge/eventbus/api/IEventBus;post(Lnet/minecraftforge/eventbus/api/Event;)Z",
                    remap = false
            ),
            // Failsafe: при несовпадении целевых точек — просто не применяемся, без краша.
            require = 0,
            expect = 0
    )
    private boolean glasspierce$postAmmoHitBlock(IEventBus bus, Event event) {
        // Не наш режим или расширение выключено (maxGlassBreaks = 0): работаем прозрачно.
        if (!Config.extensionEnabled() || Config.mode() != Config.Mode.MIXIN) {
            return bus.post(event);
        }

        if (!(event instanceof AmmoHitBlockEvent hit)) {
            return bus.post(event); // страховка; здесь всегда AmmoHitBlockEvent
        }

        Level level = hit.getLevel();
        BlockState state = hit.getState();
        EntityKineticBullet ammo = hit.getAmmo();

        if (level == null || level.isClientSide) {
            return bus.post(event);
        }
        // Уважаем конфиг TaCZ: если админ TaCZ выключил разрушение стекла — не вмешиваемся.
        if (Config.respectTaCZDestroyGlass() && !com.tacz.guns.config.common.AmmoConfig.DESTROY_GLASS.get()) {
            return bus.post(event);
        }

        if (!PierceLogic.processGlassHit(level, hit.getHitResult(), state, ammo)) {
            // НЕ наше стекло: штатный путь TaCZ полностью (супер-код, частицы, discard).
            return bus.post(event);
        }

        // Логика сама разрушила все стёкла на пути этого тика и/или погасила пулю.
        // Учёт и эффекты уже выполнены внутри; другим слушателям событие постили не будем:
        // блоки уже разрушены, их повторное destroyBlock — безопасный no-op.
        glasspierce$logOnce();
        return true;
    }

    @Unique
    private static void glasspierce$logOnce() {
        if (!glasspierce$loggedActive) {
            glasspierce$loggedActive = true;
            GlassPierceMod.LOGGER.info("glasspierce: Mixin-режим активен (первое пробитие стекла прошло)");
        }
    }
}
