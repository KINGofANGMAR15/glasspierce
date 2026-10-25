package com.fizik.glasspierce;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Серверный конфиг (ForgeConfigSpec; Forge 1.20.1 не использует аннотацию {@code @Config} —
 * это Fabric-механизм, поэтому конфиг собран через ForgeConfigSpec.Builder).
 *
 * <p>Хранит кеш распарсенных тегов и блэклиста, обновляемый на
 * {@link ModConfigEvent.Loading}/{@link ModConfigEvent.Reloading}.</p>
 */
@Mod.EventBusSubscriber(modid = GlassPierceMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {

    /** Механизм работы аддона. Выбирается конфигом "mode". */
    public enum Mode {
        /** Вариант B: слушатель AmmoHitBlockEvent, без Mixin. */
        EVENT,
        /** Вариант A: Mixin-хук в EntityKineticBullet#onHitBlock. */
        MIXIN
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue MAX_GLASS_BREAKS = BUILDER
            .comment(
                    "Сколько стеклянных блоков/панелей подряд может пробить ОДНА пуля.",
                    "Стоковый TaCZ эквивалентен значению 1 (ломает одно стекло и погибает в нём).",
                    "0 — полностью отключает расширение (работает ванильный TaCZ).",
                    "Тест: стена из 6 стеклянных панелей -> ровно 5 разбиваются, шестая остаётся целой.",
                    "После каждого пробития пуля продолжает полёт (событие отменяется).")
            .defineInRange("maxGlassBreaks", 5, 0, 1024);

    private static final ForgeConfigSpec.DoubleValue DAMAGE_MULTIPLIER_PER_BREAK = BUILDER
            .comment(
                    "Множитель урона за каждое разрушенное стекло. 1.0 = без потерь,",
                    "0.8 = -20% за блок, 0.5 = вполовину за каждый блок.",
                    "Применяется через TaCZ API: EntityHurtByGunEvent.Pre#setBaseAmount (см. GunDamageModifierListener).",
                    "Итоговый множитель = pow(m, число пробитых стёкол этой пулей).")
            .defineInRange("damageMultiplierPerBreak", 1.0D, 0.0D, 10.0D);

    private static final ForgeConfigSpec.DoubleValue VELOCITY_MULTIPLIER_PER_BREAK = BUILDER
            .comment(
                    "Множитель оставшейся скорости пули за каждое разрушенное стекло (1.0 = без потерь).",
                    "Скорость меняется только на серверной сущности; клиентский визуал пули TaCZ",
                    "рисуется по собственному расчёту и при не 1.0 может слегка расходиться с логикой.")
            .defineInRange("velocityMultiplierPerBreak", 1.0D, 0.0D, 10.0D);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> GLASS_BLOCK_TAGS = BUILDER
            .comment(
                    "Дополнительные блок-теги, которые считаются 'стеклом' в дополнение",
                    "к штатному набору TaCZ (AbstractGlassBlock / StainedGlassPaneBlock / IronBarsBlock).",
                    "Несуществующие теги безопасно игнорируются (state.is(tag) == false).",
                    "Тег 'c:...' из общего каталога Common Tags при желании добавьте сами:")
            .defineList("glassBlockTags",
                    List.of("forge:glass", "forge:glass_panes"),
                    (Predicate<Object>) o -> o instanceof String s && !s.isBlank());

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_BLOCKS = BUILDER
            .comment(
                    "Блоки-исключения: даже если блок прошёл по тегу или классу, он не будет пробит.",
                    "bedrock и barrier технически непробиваемы (getDestroySpeed < 0) и так отсекаются;",
                    "тут они для явности и для защиты от тегов, добавляющих их вручную.")
            .defineList("blacklistedBlocks",
                    List.of("minecraft:bedrock", "minecraft:barrier"),
                    (Predicate<Object>) o -> o instanceof String s && !s.isBlank());

    private static final ForgeConfigSpec.EnumValue<Mode> MODE = BUILDER
            .comment(
                    "Механизм работы:",
                    " EVENT — слушатель AmmoHitBlockEvent (без Mixin, устойчив к обновлениям TaCZ).",
                    " MIXIN  — хук в EntityKineticBullet#onHitBlock (штатный обработчик стекла TaCZ",
                    "           отрабатывает сам; см. комментарии в EntityKineticBulletMixin).")
            .defineEnum("mode", Mode.EVENT);

    private static final ForgeConfigSpec.BooleanValue BREAK_GLASS_ON_LIMIT = BUILDER
            .comment(
                    "Что происходит со стеклом, когда лимит исчерпан и пуля упирается в него:",
                    " false — стекло остаётся целым (соответствует тесту 'стена из 6 панелей -> разбиваются ровно 5').",
                    " true  — стекло ломается штатным обработчиком TaCZ, и пуля гаснет в нём (поведение стокового TaCZ).")
            .define("breakGlassOnLimitReached", false);

    private static final ForgeConfigSpec.BooleanValue RESPECT_TACZ_DESTROY_GLASS = BUILDER
            .comment(
                    "Если в конфиге TaCZ (AmmoConfig -> DestroyGlass) разрушение стекла выключено,",
                    "не активировать и наш режим пробития (не ломать стекло, не увеличивать счётчик).")
            .define("respectTaCZDestroyGlass", true);

    private static final ForgeConfigSpec.BooleanValue CHECK_PROTECTION = BUILDER
            .comment(
                    "Мягкая защита территорий: если стреляющий — игрок, перед разрушением стекла",
                    "постится синтетический BlockEvent.BreakEvent; если защитные моды его отменили",
                    "(grief-protection и т.п.), стекло не ломается, а пуля гаснет.")
            .define("checkTerritoryProtection", true);

    private static final ForgeConfigSpec.BooleanValue USE_VANILLA_GLASS_CLASS_DETECTION = BUILDER
            .comment(
                    "Использовать штатный фильтр стекла TaCZ по классам (AbstractGlassBlock /",
                    "StainedGlassPaneBlock / IronBarsBlock) — точно тот же набор блоков,",
                    "который ломает стоковый TaCZ. Отключите, если решите ограничить пробитие",
                    "только тегами из glassBlockTags.")
            .define("useVanillaGlassDetection", true);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> AMMO_LIMITS = BUILDER
            .comment(
                    "Лимиты пробития стекла по калибру (ammoId). Формат записи: '<ammoId>=<число>'.",
                    "Если калибр не указан — используется maxGlassBreaks (выше).",
                    "0 для конкретного калибра = пробитие стекла отключено (стандартный сток TaCZ,",
                    "пуля ломает первое стекло и гаснет в нём).",
                    "Стандартные калибры пачка TaCZ: tacz:9mm, tacz:545x39, tacz:556x45, tacz:57x28,",
                    "tacz:58x42, tacz:762x25, tacz:762x39, tacz:762x54, tacz:792x57, tacz:308,",
                    "tacz:30_06, tacz:338, tacz:357mag, tacz:45acp, tacz:45_70, tacz:46x30,",
                    "tacz:500mag, tacz:50ae, tacz:50bmg, tacz:12g, tacz:22wmr, tacz:68x51fury,",
                    "tacz:40mm, tacz:rpg_rocket.",
                    "Улучшения патронов (экспансивные и пр.) дополнительно добавляют стёкла —",
                    "см. damageBonusStep ниже; взрывчатые (40mm, rpg_rocket и т.п.) не пробивают вообще.")
            .defineList("ammoLimits",
                    List.of(
                            "tacz:9mm=1",
                            "tacz:22wmr=1",
                            "tacz:45acp=1",
                            "tacz:50ae=1",
                            "tacz:57x28=1",
                            "tacz:545x39=2",
                            "tacz:556x45=2",
                            "tacz:58x42=2",
                            "tacz:46x30=2",
                            "tacz:762x25=1",
                            "tacz:762x39=2",
                            "tacz:68x51fury=3",
                            "tacz:762x54=3",
                            "tacz:308=3",
                            "tacz:30_06=3",
                            "tacz:792x57=3",
                            "tacz:338=4",
                            "tacz:45_70=3",
                            "tacz:500mag=4",
                            "tacz:12g=2",
                            "tacz:50bmg=5",
                            "tacz:357mag=2",
                            "tacz:40mm=0",
                            "tacz:rpg_rocket=0"),
                    (Predicate<Object>) o -> o instanceof String s && !s.isBlank());

    private static final ForgeConfigSpec.DoubleValue DAMAGE_BONUS_STEP = BUILDER
            .comment(
                    "Бонус за улучшения патронов (экспансивные и пр.), увеличивающие урон:",
                    "за каждые N-кратное превышение урона над базовым калибр получает +1 стекло.",
                    "Например 0.3: патроны HP/экспансивные (+30% урона) с лимитом 2 пробьют 3 стекла.",
                    "0 — выключить бонус (лимит строго по калибру).")
            .defineInRange("damageBonusStep", 0.3D, 0.0D, 10.0D);

    private static final ForgeConfigSpec.IntValue PIERCE_BONUS_MAX = BUILDER
            .comment(
                    "Бонус за TaCZ-модификатор 'pierce' (пробитие сущностей): +1 стекло за каждый",
                    "уровень pierce свыше 1, но не более максимума. Работает и для улучшения FMJ.",
                    "0 — выключен по умолчанию (чтобы не ломать настройки калибров).")
            .defineInRange("pierceBonusMax", 0, 0, 64);

    private static final ForgeConfigSpec.BooleanValue ALLOW_EXPLOSIVE_PIERCING = BUILDER
            .comment(
                    "Выключено по умолчанию и так задумано: взрывчатые боезаряды (RPG-7, 40mm,",
                    "фугасные патроны и т.п.) НЕ пробивают стекло штатной механикой — они взрываются.",
                    "Если включить, они начнут ломать стекло по лимиту вместо взрыва — обычно это",
                    "нежелательно (тандемная ракета «застрянет» в стекле). Оставьте false.")
            .define("allowExplosivePiercing", false);

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOG = BUILDER
            .comment(
                    "Подробный лог: первое попадание пули в блок и каждое решение по стеклу.",
                    "Полезно для диагностики совместимости (например, с TaCZ Tweaks).")
            .define("debugLog", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    static {
        // Применяем дефолты сразу, чтобы мод корректно работал и до загрузки конфига.
        refreshCaches();
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    public static int maxGlassBreaks() {
        return MAX_GLASS_BREAKS.get();
    }

    public static double damageMultPerBreak() {
        return DAMAGE_MULTIPLIER_PER_BREAK.get();
    }

    public static double velocityMultPerBreak() {
        return VELOCITY_MULTIPLIER_PER_BREAK.get();
    }

    public static boolean useVanillaGlassDetection() {
        return USE_VANILLA_GLASS_CLASS_DETECTION.get();
    }

    public static boolean debugLog() {
        return DEBUG_LOG.get();
    }

    public static boolean breakGlassOnLimitReached() {
        return BREAK_GLASS_ON_LIMIT.get();
    }

    public static boolean respectTaCZDestroyGlass() {
        return RESPECT_TACZ_DESTROY_GLASS.get();
    }

    public static boolean checkTerritoryProtection() {
        return CHECK_PROTECTION.get();
    }

    public static Mode mode() {
        return MODE.get();
    }

    public static List<TagKey<Block>> cachedGlassTags() {
        return CACHED_GLASS_TAGS;
    }

    public static boolean isBlacklisted(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return BLACKLISTED_SET.contains(key);
    }

    /** Расширение полностью отключено: maxGlassBreaks = 0. */
    public static boolean extensionEnabled() {
        return maxGlassBreaks() > 0;
    }

    /** Лимит стёкол для конкретного калибра, либо {@code null} — брать fallback (maxGlassBreaks). */
    @Nullable
    public static Integer ammoLimit(String ammoId) {
        return AMMO_LIMITS_CACHE.get(ammoId);
    }

    /** Шаг бонуса за урон от улучшений патронов; 0 — выключен. */
    public static double damageBonusStep() {
        return DAMAGE_BONUS_STEP.get();
    }

    /** Максимум бонуса за TaCZ-модификатор pierce; 0 — выключен. */
    public static int pierceBonusMax() {
        return PIERCE_BONUS_MAX.get();
    }

    /** Разрешить взрывчатым боезарядам пробивать стекло (по умолчанию — НЕТ). */
    public static boolean allowExplosivePiercing() {
        return ALLOW_EXPLOSIVE_PIERCING.get();
    }

    // -------------------------------------------------------------------------
    // Кеши
    // -------------------------------------------------------------------------

    private static volatile List<TagKey<Block>> CACHED_GLASS_TAGS = List.of();
    private static volatile Set<ResourceLocation> BLACKLISTED_SET = Set.of();
    private static volatile Map<String, Integer> AMMO_LIMITS_CACHE = Map.of();

    private static void refreshCaches() {
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String entry : GLASS_BLOCK_TAGS.get()) {
            try {
                ResourceLocation rl = new ResourceLocation(entry.toLowerCase(Locale.ROOT));
                tags.add(TagKey.create(Registries.BLOCK, rl));
            } catch (Exception e) {
                GlassPierceMod.LOGGER.warn("glasspierce: не могу распознать тег '{}' в glassBlockTags — пропускаю", entry);
            }
        }
        Set<ResourceLocation> blacklist = new HashSet<>();
        for (String entry : BLACKLISTED_BLOCKS.get()) {
            try {
                blacklist.add(new ResourceLocation(entry.toLowerCase(Locale.ROOT)));
            } catch (Exception e) {
                GlassPierceMod.LOGGER.warn("glasspierce: не могу распознать блок '{}' в blacklistedBlocks — пропускаю", entry);
            }
        }
        // Парсим "tacz:9mm=1" -> (tacz:9mm -> 1). Допускаем пробелы и отсутствие '='.
        Map<String, Integer> ammoLimits = new HashMap<>();
        int loadedLimits = 0;
        for (String entry : AMMO_LIMITS.get()) {
            String s = entry.trim();
            int eq = s.lastIndexOf('=');
            if (eq <= 0) {
                GlassPierceMod.LOGGER.warn("glasspierce: запись '{}' в ammoLimits без '=' — пропускаю", entry);
                continue;
            }
            String id = s.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = s.substring(eq + 1).trim();
            try {
                int limit = Integer.parseInt(value);
                if (limit < 0 || limit > 1024) {
                    GlassPierceMod.LOGGER.warn("glasspierce: лимит '{}' в ammoLimits вне диапазона 0..1024 — пропускаю", entry);
                    continue;
                }
                ammoLimits.put(id, limit);
                loadedLimits++;
            } catch (NumberFormatException e) {
                GlassPierceMod.LOGGER.warn("glasspierce: не могу распознать лимит '{}' в ammoLimits — пропускаю", entry);
            }
        }
        CACHED_GLASS_TAGS = List.copyOf(tags);
        BLACKLISTED_SET = Set.copyOf(blacklist);
        AMMO_LIMITS_CACHE = Map.copyOf(ammoLimits);
        GlassPierceMod.LOGGER.debug("glasspierce: калибров в ammoLimits: {}", loadedLimits);
    }

    /**
     * Мягкая проверка совместимости: если загружены моды, которые тоже перерабатывают
     * механику пробития стекла TaCZ, — пишем в лог предупреждение.
     * Принудительно отключать свой функционал не совсем корректно (админ мог настроить
     * их вместе), поэтому решение — предупредить и оставить работать по умолчанию.
     */
    private static void checkCompat() {
        String[] suspects = {
                "tacz_tweaks",      // TaCZ Tweaks
                "tacz_mechanics",   // TaCZ Mechanics
                "block_durability", // Block-Durability
                "blockdurability"   // альтернативное написание modId
        };
        List<String> loaded = new ArrayList<>();
        for (String id : suspects) {
            if (ModList.get().isLoaded(id)) {
                loaded.add(id);
            }
        }
        if (!loaded.isEmpty()) {
            GlassPierceMod.LOGGER.warn(
                    "glasspierce: загружены моды, которые тоже могут менять пробитие стекла TaCZ: {}. " +
                            "Проверьте совместимость (двойные звуки, счётчики, лимиты) перед включением на сервере.",
                    loaded);
        }
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refreshCaches();
            checkCompat();
            GlassPierceMod.LOGGER.info(
                    "glasspierce: серверный конфиг загружен: maxGlassBreaks(fallback)={}, mode={}, damageM={}, velocityM={}, ammoLimits={}",
                    maxGlassBreaks(), mode(), damageMultPerBreak(), velocityMultPerBreak(), AMMO_LIMITS_CACHE.size());
        }
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refreshCaches();
        }
    }

    private Config() {
    }
}
