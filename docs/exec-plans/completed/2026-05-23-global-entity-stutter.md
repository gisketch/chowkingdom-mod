# Global Entity Stutter Investigation

## Goal

Find and reduce synchronized server-tick stalls that make all entities move/stop together.

## Acceptance Criteria

- Identify profiler-backed tick hotspot.
- Stop unchanged NPC state from writing to disk during routine gym trainer reconciliation.
- Reduce repeated all-entity scans in companion/vendor maintenance.
- Preserve gym trainer, companion, and vendor behavior.

## Context Links

- `src/main/kotlin/dev/gisketch/chowkingdom/gyms/GymLeagueFeature.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/npc/NpcStore.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/npc/NpcPokemonCompanions.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/shops/VendorContractFeature.kt`

## Steps

- [x] Inspect global tick hooks and profiler screenshot.
- [x] Patch gym trainer reconciliation interval and no-op store saves.
- [x] Cache NPC Pokemon companions and avoid per-NPC all-entity scans every tick.
- [x] Cache vendor sellers and avoid all-entity/per-player mob scans every 10 ticks.
- [x] Run Gradle validation.

## Validation

- [x] `./gradlew.bat test`
- [x] `./gradlew.bat build`
- [ ] In-game profiler smoke: confirm `GymLeagueFeature.reconcileTrainer -> NpcStore.clearDead -> TomlConfigIO.write` no longer dominates server tick.

## Decision Log

- Profiler pointed at disk writes from `NpcStore.clearDead()` during gym trainer reconciliation.
- `NpcStore.setEntity()` and `clearDead()` now skip `save()` when data is unchanged.
- Full gym trainer reconciliation runs every 30 seconds instead of every second; pending trainer join reconciliation still runs promptly.
- Companion/vendor entity maintenance now uses UUID caches with rare discovery scans.

## Progress Log

- 2026-05-23: Implemented profiler-driven tick hitch fixes; Gradle validation passed.
