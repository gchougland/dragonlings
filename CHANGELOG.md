# Changelog

All notable changes to this project will be documented in this file.

## [2.1.1] - 2026-04-08

### Added

- **`/dragonlings give <player> <type>`** — Spawns a tamed dragonling in front of the target player, applies Tamework tame/owner, and links it to the **Dragon Whistle** when Tamework is present (permission `dragonlings.give`). Accepts short names (`green`, `blue`, `red`, `purple`), aliases, full role ids (e.g. `Dragonling_Green`), or other registered NPC role names.
- **Per-variant tame limits** — Configurable maximum tamed dragonlings **per color per player** (defaults: **4** each for green, blue, red, purple, and template pilot). Natural taming and `/dragonlings give` both enforce the cap; a translated message is shown when the cap is reached.
- **Persisted tame accounting** — Counts are stored in **`tame_counts.json`** under the plugin data folder: incremented when a tame completes (Tamework **Custom** effect after tame, or after a successful give), and decremented when a tamed dragonling **dies** (`DeathComponent`), so limits stay correct regardless of chunk load.
- **Tamework hooks** — `TwInteractionConfig` **Tame** entries use custom requirement **`dragonlings_tame_cap`** (pre-tame check against stored counts) and custom effect **`dragonlings_tame_cap_record`** (post-tame increment), registered via reflection so the mod still loads without Tamework on the classpath.

### Changed

- **Tame cap configuration** — **`config.json`** in the plugin data directory exposes **`MaxGreen`**, **`MaxBlue`**, **`MaxRed`**, **`MaxPurple`**, and **`MaxTemplatePilot`** (integers, default `4`). If **`config.json` is missing**, defaults are applied and the file is **written on first start** so it can be edited directly.
- **Purple dragonling void projectile damage** — **`PurpleVoidProjectilePhysicalDamage`** in **`config.json`** sets absolute **physical** hit damage for the purple variant’s void orb (default **`20.0`**, minimum **`0`**). When this differs from the default, a patched copy is written under **`generated/Server/ProjectileConfigs/`** in the plugin data folder and registered as a separate projectile asset. **Restart the server** after changing this value so it can be preloaded.

### Fixed

- **Purple combat / world freeze** — Patched projectile assets are no longer loaded via **`AssetStore.loadAssetsFromPaths`** during combat or damage handling on the world tick thread (that path takes an asset-store write lock and could deadlock the **TickingThread**). The mod now **warms up** the configured void projectile once in **`JavaPlugin.start()`**; combat code only **looks up** the preloaded config.

## [2.0.1] - 2026-03-28

### Changed

- **Tamework home position** — Set Home / work anchor is resolved through Tamework’s command-links API (`getApi().commandLinks().getByNpcUuid` → `homePosition`), using reflective calls so the mod still compiles against the Curse `compileOnly` JAR. If the registry has no entry yet, **`TameworkCommandLinks`** on the NPC is used as a fallback (with **CommandBuffer**-first reads when ticking so pending ECS updates are visible).

### Fixed

- **Idle / Return Home** — With a home set, dragonlings no longer keep using the mod’s passive follow seek toward the owner. **`MarkedEntitySeekBridge`** is cleared each tick while at home unless Tamework is in **Follow**, so stale seek targets from the “no home detected” path no longer override Idle or Return Home.

## [2.0.0] - 2026-03-26

### Requirements

- **Hytale** — targets **Update 4** era server APIs (ECS, farming, blocks, particles; see fixes below).
- **Alec's Tamework!** `>= 2.5.0` — required dependency for taming, whistle commands, companion home, capture/spawner metadata, and feed/heal interactions.

### Added

- **Tamework integration** — dragonlings are tamed and owned via Tamework; **Dragon Whistle** uses `TwCommandItemConfig` (follow, hold, set/return home, etc.). NPC roles use Tamework instruction bridges and `TwInteractionConfig` per color.
- **Dragonling Crate** — Tamework spawner item (same visuals as the vanilla capture crate) to pick up and place tamed dragonlings while preserving owner and companion metadata. Recipe: vanilla capture crate + life essence at the farming bench.
- **Healing** — `TwInteractionConfig` **Feed** entries: use the correct tame essence on an owned, tamed dragonling to restore health (with proper interaction prompts).

### Changed

- **Green dragonling** harvesting uses **`FarmingUtil.harvest`** (same path as vanilla **HarvestCrop**), including multiblock and eternal regrow behavior.
- **Loot to chest** — after harvest, items gained on the NPC are moved from its inventory into the linked chest (with eternal-seed handling and full-chest overflow as before).
- **Blue / red** behaviors updated for current block and bench APIs (`ItemContainerBlock`, `ProcessingBenchBlock`, particle APIs, etc.).

### Fixed

- **Tree apples** — green dragonlings harvest wall apples on trees again: they path to the stand position under the fruit (same column), then harvest; targeting matches that ground point so seek/harvest stay consistent.
- API breakages from **Hytale Update 4** (spatial lists, container resolution, furnace progress, swim animation parsing, concurrent watering, eternal seed dupes, etc.) as listed in prior 1.0.x / 1.1.0 notes — this release rolls those fixes into the supported stack above.

## [1.0.1] - 2026-01-22

### Fixed

- Removed Swim animation from Dragonling model to resolve client-side JSON parsing error
- Fixed duplicate block entity error when multiple blue dragonlings water farmland concurrently
  - Added exception handling around `ensureBlockEntity` calls to gracefully handle race conditions
  - Blue dragonlings can now water farmland simultaneously without causing server errors
- Fixed eternal seed duplication bug when green dragonlings harvest multi-block crops (wheat, corn, tomato)
  - Upper blocks of multi-block crops are now broken without drops before resetting the crop
  - Prevents seeds from being dropped when upper blocks break after crop reset

### Improved

- Green dragonling now walks up to crops before harvesting them (similar to blue dragonling behavior)
- Added green particle effects for green dragonling harvesting
- Made `SlowDownDistance` and `StopDistance` configurable parameters for all dragonling variants
  - Green and Blue dragonlings: 1.5 and 1.0 respectively
  - Red and Purple dragonlings: 3.0 and 2.0 respectively
- Increased harvest and watering radius from 8.0 to 11.0 blocks for green and blue dragonlings
