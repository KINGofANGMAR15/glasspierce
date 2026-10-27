# TACZ Glass Piercing (glasspierce)

**Русская версия: [README.md](README.md)**

An addon for **TaCZ (Timeless and Classics Zero)** on **Minecraft Forge 1.20.1**:
a single bullet can pierce and shatter **several glass blocks in a row** (glass blocks
and glass panes), and every break plays the **same stock sound and particles** as in
vanilla TaCZ.

Key features:

- **Limit per caliber** (`ammoLimits`): 9mm — 1 glass, 5.56×45 — 2, 7.62×54 — 3, .50 BMG — 5, etc.
- **Attachment bonus**: expanding/HP ammo (+30% damage) adds +1 glass
  (`damageBonusStep`); an optional bonus for the TaCZ `pierce` modifier (FMJ).
- **Explosive ammo untouched**: RPG-7, 40mm and HE rounds do NOT pierce glass —
  they explode as normal (a tandem rocket can't get "stuck" in glass without exploding).
- All settings live in the server config `config/glasspierce-server.toml`
  (in singleplayer: `saves/<world>/serverconfig/glasspierce-server.toml`).
- **Server-side mod**: install it on the server only; in singleplayer simply put it
  into `mods` (the integrated server runs it). Clients can join a server with this
  mod **without having it installed** (`displayTest="IGNORE_ALL_VERSION"`).

---

## 1. How vanilla TaCZ works under the hood (source code analysis)

Sources: `github.com/MCModderAnchor/TACZ`, branch **`1.20.1`** (release `1.1.8-hotfix2`).

### The block-hit chain

1. `EntityKineticBullet#onBulletTick()` — server tick only; raycasts the bullet path:
   `BlockRayTrace.rayTraceBlocks(level, new ClipContext(startVec, endVec, ClipContext.Block.COLLIDER, ...))`.
2. On a block hit it calls
   **`EntityKineticBullet#onHitBlock(BlockHitResult, Vec3, Vec3)`** (~line 478):

   ```java
   if (MinecraftForge.EVENT_BUS.post(new AmmoHitBlockEvent(this.level(), result, state, this))) {
       return;              // event canceled -> early exit: bullet SURVIVES and keeps flying
   }
   super.onHitBlock(result);
   // ...bullet hole decal (BulletHoleOption), ignition, etc. ...
   this.discard();          // event NOT canceled: the bullet DIES on this block
   ```

3. The event is `com.tacz.guns.api.event.server.AmmoHitBlockEvent` (cancelable, server-side).
4. Stock glass destruction is done by the listener
   `com.tacz.guns.event.ammo.DestroyGlassBlock`: if `AmmoConfig.DESTROY_GLASS == true`
   and the hit block is `AbstractGlassBlock` / `StainedGlassPaneBlock` / `IronBarsBlock`
   (instrument `HAT`), it calls `level.destroyBlock(pos, false, ammo.getOwner())`.

### Where the "only 1 glass" limit comes from

Two reasons, both confirmed by bytecode:

1. **The event is never canceled.** TaCZ has no counter at all: the stock glass listener
   breaks the block but does **not cancel** `AmmoHitBlockEvent`, so `onHitBlock` reaches
   `this.discard()`. "Keep flying" is achievable in one way only — canceling the event.
2. **The bullet teleport jump.** In `EntityKineticBullet#tick()` (~lines 267–271) the
   position is updated **unconditionally**, already after `onBulletTick()`:

   ```java
   onBulletTick();                       // raycast + onHitBlock (our event) happens here
   ...
   this.setPos(getX()+x, getY()+y, getZ()+z);   // jump by the FULL deltaMovement!
   ```

   A bullet is fast (dozens of blocks per tick), while a per-tick raycast only finds the
   **first** block on the path. So after the first glass is pierced and the event canceled,
   the bullet "teleports" beyond the entire glass wall and the next windows become
   unreachable. (Vanilla `clip` returns an "inside-hit" for the containing block when a ray
   starts inside a solid, so no digging through stone — but skipped windows stay intact.)
   That is why this addon **processes the remainder of the current tick's trajectory itself**
   (see section 3).

### Why stock sounds/particles are preserved automatically

Destruction sound and particles are not emitted by the TaCZ listener; they come from the
server system: `Level.destroyBlock(...)` broadcasts `levelEvent(2001)` (breaking sound +
particles) to all clients. The stock TaCZ listener and this addon use the exact same entry
point, so the effect is bit-for-bit identical to stock — no manual duplication.

---

## 2. Two operation mechanisms (`mode`)

All logic lives in the shared class `PierceLogic`; the mechanisms differ only in their
entry point.

### Variant A — Mixin (`mode = MIXIN`)

A `Mixin@Redirect` on `MinecraftForge.EVENT_BUS.post(...)` inside
`EntityKineticBullet#onHitBlock` (class `EntityKineticBulletMixin`).

- When the logic decides "pierce" (one or several glasses along this tick's path),
  the mixin answers "canceled" to the caller → the stock early `return` runs without
  `super.onHitBlock + discard`; the addon itself performs the block destruction.
- Non-glass (stone, dirt, etc.) is handled by the **full stock TaCZ flow** (decal
  particles, discard) — the addon does not interfere.
- Every injector is marked `require = 0, expect = 0`, `remap = false`: when TaCZ updates,
  the mod silently skips its hook instead of crashing (in `EVENT` mode the listener
  keeps working regardless).

### Variant B — event (`mode = EVENT`, default)

`AmmoHitBlockListener` listens to `AmmoHitBlockEvent` with `EventPriority.HIGHEST`.

- **The primary mode**, because: (1) it does not depend on TaCZ internals — update-proof;
  (2) canceling the event is the single stock way to "keep flying", so this control point
  is API-stable; (3) `HIGHEST` priority + `receiveCanceled = false` on the stock listener
  guarantee the glass is never destroyed twice.
- Canceling the event → `onHitBlock` exits early → the bullet keeps flying.

### Shared algorithm (PierceLogic) in both modes

1. **Gates:** explosive ammo (the `explosion` field of the bullet) → do not interfere
   (stock explosion); `maxGlassBreaks = 0` → addon disabled; TaCZ `DestroyGlass` disabled →
   do not interfere; the block is not "glass" → do not interfere.
2. **This bullet's limit** (`pierceLimitFor`, cached in the bullet's NBT): the caliber
   limit (`ammoLimits`) or `maxGlassBreaks` (fallback), plus the damage-based attachment
   bonus and the optional `pierce` bonus.
3. **Iterating the trajectory within the tick:** from the hit point, the addon traces the
   remainder of this tick's path with a ray (`Level.clip`, `ClipContext.Block.COLLIDER`)
   and shatters every glass block with the stock call
   `level.destroyBlock(pos, false, owner)` until the limit runs out, a non-glass block
   appears (the bullet dies — stock behavior), or no more blocks remain on this tick's
   path (the bullet keeps flying; remaining glasses are caught on subsequent ticks).

The counter and the limit live in the **bullet's persistent data**
(`Entity#getPersistentData()`, tag `ForgeData`, keys `glasspierce:brokenGlass` and
`glasspierce:limit`): every new bullet gets fresh NBT → the counter automatically resets
on each shot, never leaks between bullets, and needs no cleanup on `EntityLeaveLevelEvent`.

---

## 3. Config (`glasspierce-server.toml`)

| Option | Default | Description |
|---|---|---|
| `maxGlassBreaks` | `5` | **Fallback** for calibers not listed in `ammoLimits`. `0` disables the addon entirely. |
| `ammoLimits` | default pack values | Limit per caliber, entries like `"tacz:9mm"=1`. `0` for a caliber disables piercing for it (stock TaCZ). |
| `damageBonusStep` | `0.3` | Attachment bonus: every +30% damage over the caliber's base = +1 glass (expanding/HP ×1.3 → +1). `0` disables. |
| `pierceBonusMax` | `0` (off) | Optional bonus for the TaCZ `pierce` modifier (+1 glass per level above 1; FMJ adds +2 pierce). |
| `allowExplosivePiercing` | `false` | Explosive rounds (RPG-7, 40mm, HE) do NOT pierce glass — stock explosion. Enabling is not recommended (a rocket would "splat" on glass without exploding). |
| `damageMultiplierPerBreak` | `1.0` | Damage multiplier per broken glass (0.8 = −20% per block). Via the TaCZ API: `EntityHurtByGunEvent.Pre#setBaseAmount`. Total = `pow(m, count)`. |
| `velocityMultiplierPerBreak` | `1.0` | Bullet velocity multiplier per broken glass (server-side entity). |
| `glassBlockTags` | `[forge:glass, forge:glass_panes]` | Extra block tags treated as "glass" (missing tags are safely ignored). |
| `blacklistedBlocks` | `[minecraft:bedrock, minecraft:barrier]` | Excluded blocks (blocks with `getDestroySpeed < 0` are always rejected too). |
| `mode` | `EVENT` | Mechanism: `EVENT` (variant B) or `MIXIN` (variant A). |
| `breakGlassOnLimitReached` | `false` | When the limit is exhausted and the bullet hits a glass: `false` — glass stays intact, bullet dies; `true` — the glass also breaks via the stock call, bullet dies (stock behavior). |
| `respectTaCZDestroyGlass` | `true` | If TaCZ's `AmmoConfig.DestroyGlass` is disabled, the addon does not interfere. |
| `breachingCompat` | `true` | Compatibility with **TaCZ Tactical Breaching** (see section 4): the addon no longer breaks glass itself; breaching's shatter animation and glass HP system handle it, while this addon's per-caliber limit still caps how many panes one bullet may pass. |
| `checkTerritoryProtection` | `true` | If the shooter is a player: a synthetic `BlockEvent.BreakEvent` is posted before breaking; if a protection mod cancels it, the glass stays intact and the bullet dies. |
| `useVanillaGlassDetection` | `true` | TaCZ's stock class filter (AbstractGlassBlock / StainedGlassPaneBlock / IronBarsBlock) in addition to the tags. |
| `debugLog` | `true` | Verbose decision log (first event, every pierce, rejection reasons, caliber limits). Better to disable on production servers after debugging. |

Default limits for the stock TaCZ pack: `9mm/22wmr/45acp/50ae/57x28/762x25` — 1;
`545x39/556x45/58x42/46x30/762x39/357mag/12g` — 2; `762x54/308/30_06/792x57/68x51fury/45_70` — 3;
`338/500mag` — 4; `50bmg` — 5; `40mm/rpg_rocket` — 0 (explosive).
Calibers from other gun packs not listed in `ammoLimits` use `maxGlassBreaks`.

---

## 4. Compatibility

- Mixin injectors are marked `require = 0, expect = 0` (`remap = false`): when TaCZ updates,
  the mod does not crash, it silently degrades to stock behavior; in `EVENT` mode the addon
  keeps working independently of the mixin.
- Reading the bullet's private fields (`explosion`, `pierce`) uses an Accessor mixin wrapped
  in a defensive `try/catch`: a failure never breaks the mechanic, the addon falls back to
  the fallback limit.
- A warning is logged at startup if other addons that also rework TaCZ glass piercing are
  detected (`tacz_tweaks`, `tacz_mechanics`, `block_durability` / `blockdurability`):
  the addon stays enabled by default — the decision is left to the admin.
- TaCZ's `DestroyGlass` being turned off is respected (`respectTaCZDestroyGlass`).
- **TaCZ Tactical Breaching** (`tacz_tactical_breaching`): with `breachingCompat = true`
  (default; only takes effect when that mod is loaded) this addon no longer destroys
  glass and never cancels the event — breaching's signature glass HP damage and shard
  animation run exactly as when it is installed alone. The addon's limit still applies:
  every glass pane the bullet encounters increments the counter, and once the limit is
  exhausted the bullet dies against the next pane (pane stays whole, breaching's
  processing is blocked by cancelling the event before its handler). Mode nuances:
  `velocityMultiplierPerBreak` is not applied (breaching governs bullet speed), and
  `damageMultiplierPerBreak` counts *encountered* panes (in breaching's HP model a
  pane may survive a shot). Want this addon's instant piercing without the animations?
  Set `breachingCompat = false`.


## 5. Building

```bash
./gradlew build            # jar -> build/libs/glasspierce-1.1.0.jar
./gradlew runServer        # test server (config in run/config/)
./gradlew runClient
```

Build system: ForgeGradle 6 + MixinGradle (the mixin config is attached
via the `MixinConfigs` manifest attribute — see build.gradle). The TaCZ dependency
is pulled from the Modrinth Maven
(`maven.modrinth:timeless-and-classics-zero:1.1.8-hotfix2`).

## 6. Testing

1. Put the jar + **TaCZ 1.20.1** into the mods folder (client or server).
2. In the world, place glass blocks in a straight line (no foreign blocks in between),
   e.g. 4 blocks for `tacz:556x45` (limit 2).
3. Shoot through them with a 5.56×45 rifle: exactly **2 in a row** shatter with stock
   sounds and particles, the third stays intact, the bullet dies there.
4. Attachments: the same caliber with `ammo_mod_hp` (expanding ammo, +30% damage,
   `damageBonusStep=0.3`) pierces **3** glasses (2 + 1 bonus).
5. Explosives: RPG-7 / 40mm / HE rounds **explode** on glass as in stock
   (a tandem rocket does not turn into a "glass breaker").
6. Shoot again: a fresh bullet gets its full limit again (counter and limit reset — the
   bullet's own NBT).
7. Edge values: `maxGlassBreaks = 0` — exact stock TaCZ behavior;
   `0` for a caliber in `ammoLimits` — stock for that caliber;
   `blacklistedBlocks` containing `minecraft:glass` — the bullet never pierces glass.
