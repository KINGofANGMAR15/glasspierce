package com.fizik.glasspierce;

import com.fizik.glasspierce.mixin.EntityKineticBulletAccessor;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Общая логика пробития стекла, одинаковая для обоих механизмов (EVENT и MIXIN).
 *
 * <p><b>Как это устроено в стоковом TaCZ (факты из исходников, ветка 1.20.1):</b></p>
 * <ol>
 *     <li>{@code EntityKineticBullet#onBulletTick()} (только серверный тик) рейкастит
 *     путь пули ({@code BlockRayTrace.rayTraceBlocks(..., ClipContext.Block.COLLIDER, ...)}).</li>
 *     <li>При попадании в блок вызывается {@code EntityKineticBullet#onHitBlock(BlockHitResult, Vec3, Vec3)}
 *     (src/main/java/com/tacz/guns/entity/EntityKineticBullet.java, строка ~478):</li>
 *     <li>Там постится отменяемое событие {@code com.tacz.guns.api.event.server.AmmoHitBlockEvent}.</li>
 *     <li><b>Если событие отменено — метод сразу {@code return}:</b> пулу не трогают
 *     ({@code super.onHitBlock}, след от попадания и {@code discard()} не выполняются),
 *     пуля продолжает полёт на следующем тике.</li>
 *     <li><b>Если событие НЕ отменено</b> — выполняется {@code super.onHitBlock()},
 *     спавнятся частицы отверстия (BulletHoleOption) и в конце наступает
 *     {@code this.discard()} — пуля погибает на этом блоке.</li>
 *     <li>Разрушение стекла в стоке выполняет слушатель события:
 *     {@code com.tacz.guns.event.ammo.DestroyGlassBlock} — при включённом конфиге
 *     TaCZ ({@code AmmoConfig.DESTROY_GLASS}) и попадании в
 *     AbstractGlassBlock / StainedGlassPaneBlock / IronBarsBlock он вызывает
 *     {@code level.destroyBlock(pos, false, ammo.getOwner())}.</li>
 * </ol>
 *
 * <p><b>Где сидит ограничение "только 1 блок"</b>: у TaCZ нет счётчика — ограничение
 * неявное. Стоковый слушатель стекла НЕ отменяет {@code AmmoHitBlockEvent}, поэтому
 * на первом же стекле выполняется весь {@code onHitBlock}, включая {@code discard()}:
 * пуля ломает стекло и там же погибает (записи о "продолжении полёта" в стоке нет —
 * за продолжение полёта отвечает именно отмена события).</p>
 *
 * <p><b>Почему сохранены штатные звук и частицы:</b> звук и частицы разрушения
 * в TaCZ не рисуются вручную — их вызывает система: серверный вызов
 * {@link Level#destroyBlock(BlockPos, boolean, Entity)} транслируется как levelEvent 2001
 * (разбитие блока: звук подкласса + частицы из SoundType) всем клиентам. И стоковый
 * обработчик TaCZ, и мы используем одну и ту же ванильную точку входа, поэтому
 * эффект идентичен стоку без дублирования.</p>
 */
public final class PierceLogic {

    /** Ключ счётчика в persistent data пули (Entity#getPersistentData, тег "ForgeData"). */
    public static final String NBT_KEY = "glasspierce:brokenGlass";
    /** Ключ кешированного лимита этой пули (калибр + бусты считаются один раз в первом попадании). */
    public static final String NBT_LIMIT_KEY = "glasspierce:limit";

    private PierceLogic() {
    }

    // -------------------------------------------------------------------------
    // Счётчик пробитых стёкол на пуле
    // -------------------------------------------------------------------------

    /**
     * Текущий счётчик пробитых стекол для конкретной пули.
     * Храним на самой сущности (NBT), а не в Map<UUID,Integer>:
     * у каждой новой пули — свежий Entity и свежий NBT, поэтому счётчик:
     * 1) автоматически сбрасывается при новом выстреле (новый экземпляр пули);
     * 2) не "перетекает" между пулями;
     * 3) не течёт по памяти — запись умирает вместе с сущностью
     *    (никакой очистки в EntityLeaveLevelEvent не требуется).
     */
    public static int brokenGlassCount(Entity bullet) {
        CompoundTag data = bullet.getPersistentData();
        return Math.max(data.getInt(NBT_KEY), 0);
    }

    public static void setBrokenGlassCount(Entity bullet, int count) {
        bullet.getPersistentData().putInt(NBT_KEY, Math.max(0, count));
    }

    // -------------------------------------------------------------------------
    // Лимит пули: калибр (ammoId) + буст от улучшений патронов
    // -------------------------------------------------------------------------

    /**
     * Итоговый лимит стёкол ЭТОЙ пули. Для каждой пули считается один раз (при первом
     * попадании) и кешируется в её persistent data.
     *
     * <p><b>Составляющие:</b></p>
     * <ol>
     *   <li><b>Калибр</b> — конфиг ammoLimits (ammoId -&gt; лимит), иначе maxGlassBreaks.</li>
     *   <li><b>Буст улучшений</b> — сравниваем текущий урон пули (уже включающий
     *   TaCZ-модификаторы улучшений, например HP/экспансивные +30%) с базовым уроном
     *   калибра из данных пушки. Каждые {@code damageBonusStep} превышения дают +1 стекло.
     *   Именно так «экспансивные патроны» пробивают больше, чем чистый калибр.</li>
     *   <li><b>Буст за TaCZ pierce</b> — опционально (pierceBonusMax, по умолчанию 0),
     *   для улучшений типа FMJ, дающих +2 к пробитию сущностей.</li>
     * </ol>
     *
     * <p><b>Взрывчатые боеприпасы (RPG-7, 40mm, HE)</b> обрабатываются отдельно:
     * {@link #isExplosiveBlocked} — по умолчанию они вообще не участвуют в пробитии.</p>
     */
    public static int pierceLimitFor(EntityKineticBullet ammo) {
        CompoundTag data = ammo.getPersistentData();
        if (data.contains(NBT_LIMIT_KEY)) {
            return Math.max(0, data.getInt(NBT_LIMIT_KEY));
        }
        int limit = computeLimit(ammo);
        data.putInt(NBT_LIMIT_KEY, Math.max(0, limit));
        return Math.max(0, limit);
    }

    private static int computeLimit(EntityKineticBullet ammo) {
        // 1) База: лимит калибра (ammoLimits), иначе maxGlassBreaks (fallback).
        Integer byAmmo = Config.ammoLimit(ammo.getAmmoId().toString());
        int limit = byAmmo != null ? byAmmo : Config.maxGlassBreaks();
        if (limit <= 0) {
            // Явный 0 для калибра или maxGlassBreaks = 0: пробитие выключено.
            return 0;
        }

        // 2) Буст улучшений через реальный урон пули.
        double step = Config.damageBonusStep();
        if (step > 0.0D) {
            try {
                float baseDamage = baseBulletDamage(ammo);
                float currentDamage = ammo.getDamage(ammo.position());
                if (baseDamage > 1.0E-6F && currentDamage > 0.0F) {
                    double ratio = currentDamage / baseDamage;
                    if (ratio > 1.0D) {
                        int bonus = (int) Math.floor((ratio - 1.0D) / step);
                        if (bonus > 0) {
                            debugMsg("урон " + currentDamage + " против базового " + baseDamage
                                    + " (" + (int) (ratio * 100) + "%) — +" + bonus
                                    + " стёкол от улучшений патронов");
                            limit += bonus;
                        }
                    }
                }
            } catch (Throwable t) {
                // Любые проблемы с TaCZ API (нет индекса пушки и т.п.) не должны ломать
                // механику — просто считаем без бонуса.
                debugMsg("не удалось оценить бонус урона: " + t.getMessage());
            }
        }

        // 3) Опциональный бонус за TaCZ 'pierce' (FMJ и т.п.).
        int pierceBonusMax = Config.pierceBonusMax();
        if (pierceBonusMax > 0) {
            try {
                int pierce = ((EntityKineticBulletAccessor) (Object) ammo).glasspierce$pierce();
                int bonus = Math.min(Math.max(pierce - 1, 0), pierceBonusMax);
                if (bonus > 0) {
                    debugMsg("pierce=" + pierce + " — +" + bonus
                            + " стёкол (pierceBonusMax=" + pierceBonusMax + ")");
                    limit += bonus;
                }
            } catch (Throwable t) {
                debugMsg("не удалось прочесть pierce: " + t.getMessage());
            }
        }
        return limit;
    }

    /**
     * Базовый урон калибра (без улучшений): bullet.damage из данных пушки,
     * умноженный на глобальный множитель TaCZ DAMAGE_BASE_MULTIPLIER — ровно как
     * формирует его сам TaCZ (см. DamageModifier#initCache).
     */
    private static float baseBulletDamage(EntityKineticBullet ammo) {
        float damageBaseMultiplier = com.tacz.guns.config.sync.SyncConfig.DAMAGE_BASE_MULTIPLIER.get().floatValue();
        Float damage = TimelessAPI.getCommonGunIndex(ammo.getGunId())
                .map(index -> index.getGunData().getBulletData().getDamageAmount() * damageBaseMultiplier)
                .orElse(0.0F);
        return damage;
    }

    /**
     * Взрывчатые боеприпасы (RPG-7, 40mm, фугасные HE) не должны пробивать стекло:
     * штатный TaCZ взрывает их прямо в месте попадания, и отмена события вместо взрыва
     * была бы багом (тандемная ракета «застрянет» в стекле). По умолчанию возвращаем
     * «не вмешиваться» (true = заблокировано), кроме случая allowExplosivePiercing = true.
     */
    public static boolean isExplosiveBlocked(EntityKineticBullet ammo) {
        if (Config.allowExplosivePiercing()) {
            return false;
        }
        try {
            boolean explosive = ((EntityKineticBulletAccessor) (Object) ammo).glasspierce$isExplosive();
            if (explosive) {
                debugMsg("боеприпас взрывной (RPG-7/40mm/HE) — не вмешиваемся: стоковый взрыв");
            }
            return explosive;
        } catch (Throwable t) {
            // Не смогли прочесть флаг -> считаем не-взрывным и работаем как раньше.
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Определение "стекла" и общая точка решения
    // -------------------------------------------------------------------------

    /**
     * Является ли блок "пробиваемым стеклом" по правилам аддона:
     * не воздух, не непробиваемый (getDestroySpeed < 0 — bedrock/барьер),
     * не в блэклисте конфига, и
     * а) штатный класс-фильтр TaCZ (AbstractGlassBlock / StainedGlassPaneBlock /
     *    IronBarsBlock с instrument HAT — точный дубль условия из DestroyGlassBlock), либо
     * б) блок подошёл под один из настроенных тегов (forge:glass, forge:glass_panes, ...).
     */
    public static boolean isBreakableGlass(Level level, BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }
        // Технически непробиваемые (bedrock, barrier и прочие getDestroySpeed == -1):
        if (state.getDestroySpeed(level, pos) < 0) {
            return false;
        }
        if (Config.isBlacklisted(state)) {
            return false;
        }
        if (Config.useVanillaGlassDetection()) {
            // Точный дубль класса-условия из стокового DestroyGlassBlock TaCZ:
            // разрушаем ровно тот же набор блоков, что и сток, — эффект внешне не отличается.
            net.minecraft.world.level.block.Block block = state.getBlock();
            NoteBlockInstrument instrument = state.instrument();
            if (block instanceof AbstractGlassBlock
                    || block instanceof StainedGlassPaneBlock
                    || (block instanceof IronBarsBlock && instrument.equals(NoteBlockInstrument.HAT))) {
                return true;
            }
        }
        List<TagKey<Block>> tags = Config.cachedGlassTags();
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Единая точка принятия решения для EVENT и MIXIN механизмов.
     *
     * <p><b>ВАЖНО: почему ломаем ВСЕ стёкла ПОДРЯД внутри одного события.</b></p>
     * В стоковом {@code EntityKineticBullet#tick()} (ветка 1.20.1, строки ~267-271)
     * позиция пули обновляется БЕЗУСЛОВНО после {@code onHitBlock}:
     * <pre>
     *     onBulletTick();              // здесь рейкаст + onHitBlock (наше событие)
     *     ...
     *     this.setPos(getX()+x, getY()+y, getZ()+z);   // прыжок на ПОЛНЫЙ deltaMovement!
     * </pre>
     * Пуля быстрая (десятки блоков за 1 тик). Рейкаст за тик находит только ПЕРВЫЙ блок,
     * поэтому после отмены события пуля "телепортируется" за всю стеклянную стену (и даже
     * за камень за ней) — следующее стекло становится недостижимым. Именно отсюда симптом
     * "пробивает только 1 блок". Проверено по байткоду: если луч начинается ВНУТРИ
     * твёрдого блока, vanilla clip возвращает hit по нему (inside-hit) — туннелирования
     * нет, но перепрыгнутые стёкла остаются целыми.
     *
     * <p>Решение: в момент первого попадания сами проходим лучом ({@link Level#clip})
     * остаток траектории ЭТОГО тика (длиной |deltaMovement|) и последовательно ломаем
     * каждое стекло на пути (лимит, защита территории и breakGlassOnLimitReached
     * соблюдаются на каждом шаге).</p>
     *
     * @return true — событие {@code AmmoHitBlockEvent} нужно отменить
     *         (мы уже вмешались: хотя бы один блок разрушен или пуля погашена),
     *         false — не вмешиваться: всё дальше делает стоковый TaCZ.
     */
    public static boolean processGlassHit(Level level, BlockHitResult hitResult, BlockState state, EntityKineticBullet ammo) {
        if (level == null || hitResult == null || state == null || ammo == null) {
            debug(state, "пустые аргументы EVENT/MIXIN — false");
            return false;
        }
        if (level.isClientSide) {
            return false; // событие постится только на сервере, но страховка не помешает
        }
        // Взрывчатые (RPG-7, 40mm, фугасные HE) НЕ пробивают стекло: пусть взрывается штатно.
        if (isExplosiveBlocked(ammo)) {
            return false;
        }
        if (!Config.extensionEnabled()) {
            debug(state, "maxGlassBreaks = 0 — расширение выключено, false");
            return false; // maxGlassBreaks == 0 — расширение полностью выключено
        }
        // Уважаем решение админа TaCZ: он сам выключил разрушение стекла — не вмешиваемся.
        if (Config.respectTaCZDestroyGlass() && !AmmoConfig.DESTROY_GLASS.get()) {
            debug(state, "TaCZ DestroyGlass выключен — false");
            return false;
        }
        BlockPos pos = hitResult.getBlockPos();
        if (!isBreakableGlass(level, state, pos)) {
            // НЕ наше стекло: позволяем стоковому обработчику TaCZ выполнить всё как обычно.
            debug(state, "не распознано как стекло — false (стоковый путь)");
            return false;
        }

        // Лимит этой пули: калибр + бусты улучшений (кешируется на пуле).
        int max = pierceLimitFor(ammo);
        if (max <= 0) {
            debug(state, "лимит для калибра " + ammo.getAmmoId() + " = 0 — стоковый путь");
            return false;
        }

        int broken = brokenGlassCount(ammo);

        // Курсор по траектории: точка попадания в текущий блок.
        Vec3 cursor = hitResult.getLocation();
        // Первая итерация цикла обрабатывает блок, в который мы уже попали.
        BlockPos currentPos = pos;
        BlockState currentState = state;

        while (true) {
            // --- Лимит исчерпан ---------------------------------------------------
            if (broken >= max) {
                debug(currentState, "лимит исчерпан (" + broken + "/" + max
                        + "), breakGlassOnLimitReached=" + Config.breakGlassOnLimitReached()
                        + " — пуля гаснет");
                if (Config.breakGlassOnLimitReached()) {
                    // Стоковый финал: и это стекло тоже ломаем тем же штатным вызовом
                    // (звук/частицы как в стоке), после чего пуля гаснет.
                    level.destroyBlock(currentPos, false, ammo.getOwner());
                }
                ammo.discard();
                return true;
            }

            // --- Защита территории -------------------------------------------------
            if (!protectionBreakEventAllows(level, currentPos, currentState, ammo.getOwner())) {
                debug(currentState, "территория защищена (BreakEvent отменён) — пуля гаснет");
                ammo.discard();
                return true;
            }

            // --- Ломаем штатным вызовом (звук + частицы уровня levelEvent 2001) ----
            if (!level.destroyBlock(currentPos, false, ammo.getOwner())) {
                debug(currentState, "destroyBlock вернул false — пуля гаснет");
                ammo.discard();
                return true;
            }

            broken++;
            setBrokenGlassCount(ammo, broken);
            double velocityMult = Config.velocityMultPerBreak();
            if (velocityMult != 1.0D && velocityMult > 0.0D) {
                ammo.setDeltaMovement(ammo.getDeltaMovement().scale(velocityMult));
            }
            debug(currentState, "ПРОБИТО: broken " + broken + "/" + max + " (звук/частицы штатные)");

            // --- Продвигаемся по остатку траектории этого тика ----------------------
            Vec3 delta = ammo.getDeltaMovement();
            double travel = delta.length();
            if (travel < 1.0E-4D) {
                debug(null, "скорость ~0 — стекло сломано, пуля долетит сама (отмена события)");
                // Отмена всё равно нужна: мы уже разрушили этот блок, стоковый путь
                // (discard после super.onHitBlock) здесь не нужен.
                return true;
            }
            Vec3 norm = delta.scale(1.0D / travel);
            BlockHitResult next = level.clip(new ClipContext(
                    cursor, cursor.add(norm.scale(travel)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ammo));
            if (next.getType() == HitResult.Type.MISS) {
                // В пределах этого тика блоков больше нет: пуля летит дальше,
                // следующее стекло (если есть) поймаем на следующем тике —
                // счётчик на пуле сохраняется.
                debug(null, "в пределах тика дальше блоков нет — пуля летит дальше");
                return true;
            }
            cursor = next.getLocation();
            currentPos = next.getBlockPos();
            currentState = level.getBlockState(currentPos);
            if (!isBreakableGlass(level, currentState, currentPos)) {
                // За стеклом не стекло: стоковый TaCZ здесь гасит пулю —
                // повторяем его финал (discard без super.onHitBlock; декали не будет).
                debug(currentState, "за стеком не стекло — стоковый финал (пуля гаснет)");
                ammo.discard();
                return true;
            }
            // Следующий блок по траектории — тоже стекло: цикл продолжается,
            // учитывая его в счётчик на следующей итерации.
        }
    }

    /** Диагностический лог без привязки к блоку (включается конфигом debugLog). */
    private static void debugMsg(String message) {
        if (!Config.debugLog()) {
            return;
        }
        GlassPierceMod.LOGGER.info("glasspierce: {}", message);
    }

    /** Диагностический лог решений (включается конфигом debugLog). */
    private static void debug(@Nullable BlockState state, String message) {
        if (!Config.debugLog()) {
            return;
        }
        String block = state == null ? "?"
                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        GlassPierceMod.LOGGER.info("glasspierce: [{}] {}", block, message);
    }

    /**
     * Мягкая защита территорий: если стреляющий — игрок, постим синтетический
     * {@link BlockEvent.BreakEvent} и уважаем его отмену защитными модами.
     * Пост-фактум отмену вернуть нельзя, поэтому пуля в этом случае просто гаснет.
     * (public: используется и EVENT-механизмом, и MIXIN.)
     */
    public static boolean protectionBreakEventAllows(Level level, BlockPos pos, BlockState state, @Nullable Entity owner) {
        if (!Config.checkTerritoryProtection()) {
            return true;
        }
        if (owner instanceof Player player) {
            BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(level, pos, state, player);
            MinecraftForge.EVENT_BUS.post(breakEvent);
            if (breakEvent.isCanceled()) {
                return false;
            }
        }
        return true;
    }
}
