package com.fizik.glasspierce;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@link Mod @Mod}-точка входа аддона "TACZ Glass Piercing".
 *
 * <p>Мод решает одну задачу: снимает неявное ограничение TaCZ "одна пуля ломает
 * ровно одно стекло", позволяя одной пуле ({@code com.tacz.guns.entity.EntityKineticBullet})
 * пробивать до {@code maxGlassBreaks} стеклянных блоков/панелей подряд,
 * не меняя визуально-звуковое поведение (звук и частицы разрушения каждый раз
 * штатные — см. комментарии в {@link PierceLogic}).</p>
 *
 * <p>Реализованы два взаимозаменяемых механизма (переключаются серверным конфигом):
 * <ol>
 *     <li><b>EVENT</b> (рекомендуемый, по умолчанию) — слушатель
 *     {@code com.tacz.guns.api.event.server.AmmoHitBlockEvent}; не привязан к внутренностям TaCZ.</li>
 *     <li><b>MIXIN</b> — хук в {@code EntityKineticBullet#onHitBlock}; сохраняет
 *     и штатный обработчик стекла TaCZ, и штатный пост-эффекты полёта, но привязан
 *     к структуре кода TaCZ текущей версии.</li>
 * </ol></p>
 *
 * <p>Конфиг — серверный (Forge {@link ModConfig.Type#SERVER}), объявляется в конструкторе.</p>
 */
@Mod(GlassPierceMod.MODID)
public final class GlassPierceMod {

    public static final String MODID = "glasspierce";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public GlassPierceMod() {
        // Серверный конфиг: значения берутся с сервера и автоматически синхронизируются
        // Forge'ом на клиент; менять нужно на сервере (или в singleplayer).
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }
}
