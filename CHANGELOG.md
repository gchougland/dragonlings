# Changelog

All notable changes to this project will be documented in this file.

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
