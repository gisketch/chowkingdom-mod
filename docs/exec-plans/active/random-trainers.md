# Random Trainers Execution Plan

## Goal

Implement CKDM-owned wild random trainer spawning and battling without enabling RCT Mod progression, level caps, or persistence systems.

## Implemented Scope

1. CKDM owns trainer catalog, scaling, spawning, defeat tracking, dialogue, and commands.
2. RCT API is only the battle backend.
3. Natural trainers are transient entities and are not saved to world NBT.
4. Right-click opens a dialogue with `CHALLENGE` and `BYE`.
5. The generated catalog seed remains config-data-driven, but generated filler is disabled by default with `generatedPrefillSize = 0`; current gameplay uses imported real rosters only.
6. Player defeat tracking prevents exact defeated roster repeats until the pool is exhausted.
7. Runtime Prism config has imported real preset rosters from RCT and public pret decomps.
8. Imported and generated trainers use one unified data shape for category, tier, spawnability, skin folder, and body metadata.
9. Gym Leaders, generic Leaders, Rivals, Elite Four, and Champions are excluded from the random trainer catalog because they are not wild overworld trainers.
10. Double/multi trainer classes such as Twins, Couples, Double Team, Interviewers, Sis and Bro, and Crush Kin are excluded for now; current random trainers are single NPCs only.
11. Numeric placeholder names from older source data are replaced with deterministic fallback given names during import.
12. Natural spawning skips other unique trainers and weights tiers toward the player's current team level.
13. Natural spawning is dimension-configurable and defaults to Overworld only via `allowedDimensions = ["minecraft:overworld"]`.
14. Trainers freeze in place during Pokemon battles, stay in the world after normal battle end, and disable `CHALLENGE` only for players who already defeated that roster.
15. Right-clicking a random trainer gives the trainer a short dialogue focus lock so it stops and looks at the player instead of walking away while the dialogue opens.

## Commands

- `/ck randomtrainers stats`
- `/ck randomtrainers validate`
- `/ck randomtrainers reload`
- `/ck randomtrainers spawn [roster]` with roster ID autocomplete
- `/ck randomtrainers extract`
- `/ck randomtrainers despawnall`
- `/ck randomtrainers import rct <path>`

## Data Locations

- Settings: `config/gisketchs_chowkingdom_mod/random_trainers/settings.toml`
- Generation seed: `config/gisketchs_chowkingdom_mod/random_trainers/generation_seed.toml`
- Unified catalog: `config/gisketchs_chowkingdom_mod/random_trainers/catalog/<title>/<m|f|x>/<id>.toml`
- World defeat state: `world/data/gisketchs_chowkingdom_mod/random_trainers/state.json`
- Importer script: `tools/import_trainers.py`
- Catalog unifier: `tools/unify_random_trainers.py`

Trainer classes, name pools, species pools, and dialogue seeds are data, not Kotlin constants. The bundled seed at `data/gisketchs_chowkingdom_mod/random_trainers/default_generation_seed.json` is copied into the editable generation seed config on first load.

## Unified Trainer Shape

All catalog files should normalize to:

- `id`
- `name`
- `title`
- `gender`: `male`, `female`, or `any`
- `archetype`
- `region`
- `category`: `route_trainer`, `specialist`, `team`, `battle_facility`, or `unique`
- `source`
- `skinSet`: legacy single texture name
- `skinFolder`: folder path such as `ace_trainer/female`
- `tier`: `low`, `mid`, `high`, `very_high`, or `unique`
- `spawnable`: false for unique trainers such as rivals, gym leaders, Elite Four, champions, bosses, admins, and named iconic trainers
- `height`: Pehkui height scale multiplier, clamped to `0.6..1.4`
- `weight`: Pehkui width scale multiplier, clamped to `0.6..1.4`
- `bustStyle`: female-only style key, defaulting to `standard`
- `minLevel`
- `maxLevel`
- `team`
- `dialogue`

Skin PNGs resolve from `assets/gisketchs_chowkingdom_mod/textures/entity/random_trainers/<title>/<male|female>/*.png`. If multiple PNGs exist in that folder, the renderer picks one variant for the spawned trainer entity and keeps that same texture for that entity. Legacy `skinSet` still resolves as `textures/entity/random_trainers/<skinSet>.png`.

Catalog files are now owned by normalized trainer identity, not scraped source. IDs use `<title>_<gender-code>_<name>`, for example `swimmer_m_ricardo` or `ace_trainer_f_alexa`. Every loaded trainer is assigned `male` or `female` from explicit source gender, trainer name judgment, or gendered title class; no active catalog path uses `x` or `any`. Duplicate imports with the same title/name collapse into one trainer; known gender wins for identity and skin path while the richest available team data wins for the battle roster.

`/ck randomtrainers extract` prints every loaded `title | gender | skinFolder | count` pair so skin folders can be created in batches. `tools/unify_random_trainers.py` also scaffolds every loaded `skinFolder` with a `.gitkeep` placeholder when no PNG exists yet, so Windows Explorer and Git both retain the full title/gender folder set.

## Imported Runtime Catalog

Current Prism runtime catalog import counts:

- RCT Mod trainer JSON: 1422
- pret/pokered: 355
- pret/pokecrystal: 498
- pret/pokeemerald: 722
- pret/pokefirered: 569
- pret/pokeplatinum: 831
- pret/pokeheartgold: 641

Raw imported preset roster files before unification: 5038. After removing banned unique/multi trainer entries, assigning all remaining `x` trainers to male/female from names, and merging duplicate title/gender/name identities, the active Prism catalog has 2211 usable unified trainer TOML files and dry-run unification reports zero remaining duplicate groups.

`tools/import_trainers.py` expects local checkouts under `%TEMP%/ckdm-trainer-sources`, reads RCT JSON from `%LOCALAPPDATA%/Temp/rct-mod-1.21.1/common/src/main/resources/data/rctmod/trainers` when available, writes raw source-bucket files to a temporary import catalog, then runs `tools/unify_random_trainers.py` into the live Prism catalog. It imports only public source data. ROM-only games still need user-owned extracted data before import.

`tools/unify_random_trainers.py` can also be run directly after manual catalog edits. It writes a `catalog_backup_<timestamp>` folder before replacing the live catalog, updates `unify_report.json`, normalizes skin folders, and removes duplicate PNG payloads.

Importer body-scale defaults are lore-like Pehkui multipliers, not real-world meters/kg: kid classes are smaller, strong classes such as Hiker/Black Belt/Biker are wider/taller, and ace/veteran classes are slightly larger.

## Follow-up Data Work

1. Add trainer skin PNGs under `assets/gisketchs_chowkingdom_mod/textures/entity/random_trainers/<title>/<gender>/*.png`.
2. Add ROM-export import adapters for Gen 5+ when user-owned extracted data is available.
3. Add stricter Cobblemon species/move validation for imported names before battle start.
