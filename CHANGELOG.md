# Changelog

All notable changes to this project will be documented in this file.

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
