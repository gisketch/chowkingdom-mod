# NPC Player-Like Movement

## Goal

Make NPC routine movement look concurrent and purposeful instead of synchronized turn-based nudges.

## Acceptance Criteria

- Routine movement checks are jittered per NPC, not shared by modulo tick.
- Work NPCs route around their assigned workplace using the configured town-center radius as activity scale.
- Meetup NPCs route around the town center radius.
- Home and sleep movement stay tight around the bed/home.
- Moving ambient actions dwell briefly after arrival and can show emotes/balloons there.
- Existing higher-priority overrides still win.

## Context Links

- `src/main/kotlin/dev/gisketch/chowkingdom/npc/NpcSmartBrain.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/npc/NpcFeature.kt`
- `docs/NPCS.md`

## Steps

- [x] Replace shared routine cadence with per-NPC jitter.
- [x] Reuse town-center radius for work and meetup movement target selection.
- [x] Prefer farther pathable movement targets with short fallback.
- [x] Add arrival dwell/repath jitter to ambient movement actions.
- [x] Run Gradle validation.

## Validation

- [x] `./gradlew.bat test`
- [x] `./gradlew.bat build`
- [ ] In-game smoke: set town center radius, observe work and meetup NPCs moving independently and doing actions after arrival.

## Decision Log

- Use existing town-center radius for work scale; no new work-radius config.
- Fallback town activity radius is 32 blocks when no town center exists.
- Movement actions receive a travel timeout, then shorten to dwell once target is reached.

## Progress Log

- 2026-05-23: Implemented scheduler jitter, wider targets, and arrival dwell.
- 2026-05-23: Gradle `test` and `build` passed. In-game smoke remains manual.
