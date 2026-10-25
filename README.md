# TACZ Glass Piercing (glasspierce)

Аддон для **TaCZ (Timeless and Classics Zero)** на **Minecraft Forge 1.20.1**:
одна пуля может разрушать до **N** стеклянных блоков/панелей **подряд** (по умолчанию **5**),
каждое разрушение сопровождается **теми же штатными звуком и частицами**, что и в стоке.
Все настройки — в серверном конфиге `config/glasspierce-server.toml`.

---

## 1. Как это устроено в стоковом TaCZ (разбор исходников)

Исходники: `github.com/MCModderAnchor/TACZ`, ветка **`1.20.1`** (актуальный релиз 1.20.1 — `1.1.8-hotfix2`).

Цепочка:

1. `EntityKineticBullet#onBulletTick()` — только серверный тик; рейкаст пути пули:
   `BlockRayTrace.rayTraceBlocks(level, new ClipContext(startVec, endVec, ClipContext.Block.COLLIDER, ...))`.
2. При попадании в блок вызывается
   **`EntityKineticBullet#onHitBlock(BlockHitResult, Vec3, Vec3)`**
   (`src/main/java/com/tacz/guns/entity/EntityKineticBullet.java`, ~строка 478):

   ```java
   if (MinecraftForge.EVENT_BUS.post(new AmmoHitBlockEvent(this.level(), result, state, this))) {
       return;              // событие отменено -> ранний выход: пуля ЖИВА и летит дальше
   }
   super.onHitBlock(result);
   // ...след пули (BulletHoleOption), воспламенение и т.п. ...
   this.discard();          // событие НЕ отменено: пуля ГИБНЁТ на этом блоке
   ```

3. Событие — `com.tacz.guns.api.event.server.AmmoHitBlockEvent` (отменяемое, серверное).
4. Стоковое разрушение стекла — слушатель
   `src/main/java/com/tacz/guns/event/ammo/DestroyGlassBlock.java`:
   при `AmmoConfig.DESTROY_GLASS == true` и попадании в `AbstractGlassBlock` /
   `StainedGlassPaneBlock` / `IronBarsBlock` (instrument `HAT`) он вызывает
   `level.destroyBlock(pos, false, ammo.getOwner())`.

### Где сидит лимит «1 стекло»

У TaCZ **нет счётчика** — ограничение неявное: стоковый слушатель стекла ломает блок,
но **не отменяет** `AmmoHitBlockEvent`, поэтому `onHitBlock` доходит до `this.discard()`:
пуля ломает первое стекло и гибнет в нём. «Продолжение полёта» в TaCZ достигается
ровно одним способом — **отменой события** (тогда срабатывает ранний `return`).

### Почему звук/частицы сохраняются автоматически

Звук и частицы разрушения рождает не слушатель TaCZ, а серверная система:
`Level.destroyBlock(...)` транслирует `levelEvent(2001)` (звук битого материала + частицы)
всем клиентам. И стоковый слушатель, и этот аддон вызывают одну и ту же точку входа,
поэтому эффект идентичен стоку, без ручного дублирования.

---

## 2. Два варианта решения

### Вариант A — Mixin (`mode = MIXIN`)

`Mixin@Redirect` на `MinecraftForge.EVENT_BUS.post(...)` внутри
`EntityKineticBullet#onHitBlock` (класс `EntityKineticBulletMixin`).

- При пробитии стекла реальное событие **постится как обычно** → стоковый слушатель
  TaCZ сам ломает блок → штатные звук/частицы; затем mixin лишь возвращает
  вызывающему коду «отменено», чтобы пропустить `super.onHitBlock + discard`.
- Плюсы: режим максимально «штатный» — эффект разрушения выполняет сам TaCZ.
- Минусы: привязка к сигнатуре `onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V`
  и к вызову `IEventBus.post(...)`; при обновлении TaCZ нужно перепроверить точку
  (каждый инжектор помечен `require = 0, expect = 0` и `remap = false`, поэтому
  при несовпадении мод **молча не применит** хук и не крашнется).

### Вариант B — событие (`mode = EVENT`, по умолчанию)

`AmmoHitBlockListener` слушает `AmmoHitBlockEvent` с приоритетом `HIGHEST`.

- **Выбран основным**, потому что: (1) не привязан к внутренностям TaCZ — устойчив
  к обновлениям, нет refmap-хрупкости; (2) отмена события — единственный штатный
  способ «продолжить полёт», так что эта точка контроля стабильна по API;
  (3) приоритет `HIGHEST` + `receiveCanceled=false` у стокового слушателя гарантируют,
  что стекло не разрушится дважды.
- Для каждого пробитого стекла вызывается ровно тот же
  `level.destroyBlock(pos, false, ammo.getOwner())` — те же звук и частицы,
  что в стоке. Дублирования нет: стоковый слушатель от нас получает уже
  отменённое событие и не срабатывает.

---

## 3. Конфиг (`config/glasspierce-server.toml`)

| Параметр | По умолчанию | Описание |
|---|---|---|
| `maxGlassBreaks` | `5` | Сколько стекол подряд пробивает одна пуля. `0` — отключить аддон. |
| `damageMultiplierPerBreak` | `1.0` | Множитель урона за каждое стекло (0.8 = −20% за блок). Через TaCZ API: `EntityHurtByGunEvent.Pre#setBaseAmount`. Итог = `pow(m, count)`. |
| `velocityMultiplierPerBreak` | `1.0` | Множитель скорости пули за каждое стекло (серверная сущность). |
| `glassBlockTags` | `[forge:glass, forge:glass_panes]` | Дополнительные теги «стекла» (несуществующие теги безопасно игнорируются). |
| `blacklistedBlocks` | `[minecraft:bedrock, minecraft:barrier]` | Блоки-исключения (кроме них автоматически отсекаются блоки с `getDestroySpeed < 0`). |
| `mode` | `EVENT` | Механизм: `EVENT` (вариант B) или `MIXIN` (вариант A). |
| `breakGlassOnLimitReached` | `false` | На лимите: `false` — стекло цело, пуля гаснет; `true` — сток TaCZ (стекло ломается, пуля гаснет в нём). |
| `respectTaCZDestroyGlass` | `true` | Если админ TaCZ выключил `AmmoConfig.DestroyGlass`, наш аддон не вмешивается. |
| `checkTerritoryProtection` | `true` | Если стреляющий игрок: перед разрушением постится синтетический `BlockEvent.BreakEvent`; отмена защитным модом = стекло цело, пуля гаснет. |
| `useVanillaGlassDetection` | `true` | Штатный класс-фильтр TaCZ (AbstractGlassBlock/StainedGlassPaneBlock/IronBarsBlock) в дополнение к тегам. |

Счётчик пробитых стекол хранится в **persistent data самой пули**
(`Entity#getPersistentData()`, тег `ForgeData`, ключ `glasspierce:brokenGlass`):
у каждой новой пули свой NBT → счётчик автоматически «сбрасывается» при новом
выстреле, не перетекает между пулями и не требует очистки по `EntityLeaveLevelEvent`.

---

## 4. Совместимость

- Mixin-инжекторы помечены `require = 0, expect = 0` (`remap = false`): при обновлении TaCZ
  мод не крашнется, а молча ослабнет до стокового поведения; в режиме `EVENT`
  аддон продолжает работать независимо от mixin.
- При загрузке мода логируется предупреждение, если обнаружены аддоны, которые
  тоже перерабатывают пробитие стекла (`tacz_tweaks`, `tacz_mechanics`,
  `block_durability` / `blockdurability` — сверить фактические modId по их jar):
  функционал по умолчанию не отключается, решение оставлено админу.
- Выключение TaCZ'овского `DestroyGlass` уважается (`respectTaCZDestroyGlass`).

## 5. Сборка

```bash
./gradlew build            # jar -> build/libs/glasspierce-1.0.0.jar
./gradlew runServer        # тестовый сервер (config в run/config/)
./gradlew runClient
```

Система сборки: ForgeGradle 6 + MixinGradle (mixin-конфиг подключается
через манифест `MixinConfigs` — см. build.gradle). Зависимость TaCZ подтягивается
из Modrinth Maven (`maven.modrinth:timeless-and-classics-zero:1.1.8-hotfix2`).

## 6. Тестирование (требуемый кейс)

1. Собрать и положить jar + **TaCZ 1.20.1** в папку mods сервера (или клиента).
2. В конфиге: `maxGlassBreaks = 5` (дефолт), `breakGlassOnLimitReached = false` (дефолт).
3. В мире поставить **6 стеклянных панелей подряд** (стена одного блока толщиной).
4. Дать AKM/пистолет и выстрелить через панель (встав у стены).
5. Ожидаемо:
   - ровно **5 панелей** разрушаются, каждое разрушение — со штатными звуком и
     частицами (те же, что при обычном попадании в стекло);
   - **шестая панель остаётся целой**, пуля на ней гаснет (без звука — стекло цело);
   - `damageMultiplierPerBreak` проверить на мишени: расставив 5 панелей и мишень,
     установить значение 0.8 — урон по мишени должен упасть до `pow(0.8, 5) ≈ 33%` от базового;
   - при `breakGlassOnLimitReached = true` на лимите шестая панель ломается
     стоковым обработчиком TaCZ и пуля гаснет в ней.
6. Повторный выстрел: Ланкамиöд свежая пуля → снова 5 пробитий (счётчик resetится).
7. Крайние значения: `maxGlassBreaks = 0` — точное стоковое поведение TaCZ;
   `blacklistedBlocks` с `minecraft:glass` — пуля вообще не пробивает стекло.
