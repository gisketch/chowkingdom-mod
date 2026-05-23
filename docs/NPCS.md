# NPCs

NPCs are config-driven characters backed by a small server foundation: definitions, world state, jobs, dialog, housing contracts, and a SmartBrainLib in-world brain.

In-world NPC AI uses [SmartBrainLib](references/smartbrainlib.md). CKDM still owns dialog, shops, gifts, quests, LLM routing, and UI contracts.

Conversation-specific runtime rules live in [NPC Conversations](NPC_CONVERSATIONS.md).

Custom Gecko animation AI planning lives in [NPC Custom Animation AI](NPC_CUSTOM_ANIMATION_AI.md).

## Current Slice

- One default NPC: `finn`; additional NPC TOML files join the camping-block camper pool.
- Runtime config folder: `<game config>/gisketchs_chowkingdom_mod/npcs`. Current local playtest source of truth is the Prism instance config named in `AGENTS.md`, not repo `runs/`.
- Default file: `finn.toml`, written if missing.
- Global NPC settings: `settings.toml`, written if missing.
- `settings.toml` top-level `default_speaking_emote = "speaking"` is the fallback emote for NPC speech when no explicit emote is requested. Set it to `"none"` or blank to disable the fallback. The built-in `speaking` catalog entry has `loop_while_talking = true` and no cooldown, so dialog speech can restart the clip until the server-side talking state ends.
- Shared generic NPC quests: `generic_quests.toml`, written if missing and merged into every NPC with `missions.enabled=true`.
- Shared NPC emote catalog: `emotes.toml`, written if missing. It maps stable emote ids to PlayerAnimator/Emotecraft ids and limits which surfaces may use each emote.
- Rendering experiment: `settings.toml` `[rendering].playerlike_renderer` switches NPCs to an RCT-style vanilla `PlayerModel`/`HumanoidMobRenderer` path for EMF/ETF animation pack testing. `bettercombat_playerlike_renderer = true` switches the default render path to the Better Combat/Mob Player Animator-compatible playerlike renderer. Restart the client after changing renderer settings.
- Custom animation experiment: per-NPC `custom_animation = true` routes that entity to the GeckoLib playerlike model and bypasses the EMF-oriented playerlike renderer so CKDM owns the pose. Per-NPC `playerlike_animation = true` routes that entity to the Better Combat-compatible PlayerModel path and disables Gecko custom animation mode.
- Custom animation IDs are read from `assets/gisketchs_chowkingdom_mod/animations/npc/playerlike.animation.json`; `/npc animations reload` refreshes command suggestions and asks the caller's client to reload resources.
- Shared friendship message fallback: `friendship_messages.toml`, written if missing and used by all NPCs unless a definition overrides `friendship_messages`.
- Camping point: OPs set it with `/ck camping set`.
- Contract item: `gisketchs_chowkingdom_mod:rent_contract`.
- Job application item: `gisketchs_chowkingdom_mod:job_application`.
- Entity id: `gisketchs_chowkingdom_mod:npc`.

Use `/ck camping set` to store the camping point at the caller's position and spawn a random eligible camper from the configured NPC pool when camp is ready. Right-click an unhoused camper to open dialog and receive a rent contract. Use the rent contract on a bed to assign that NPC's home. Use the WORK dialog action to receive a job application when the NPC has no workplace; the NPC follows nearby players holding their job application. Right-click a block with it while the NPC is nearby to set that block as the workplace, after configured `work_blocks` are present nearby.

NPC routine movement is scheduled per NPC with random jitter so residents do not step together on a shared cadence. Work movement anchors on the assigned workplace but uses the current town-center radius from `/ck town_center set <radius>` as the activity scale, falling back to 32 blocks when no town center is set. Meetup movement anchors on the town center and uses that same radius. Home and sleep movement stay close to the bed/home, and moving ambient actions dwell briefly after arrival so NPCs can look around, emote, observe, or show a solo balloon before choosing another route.

## Dialog

NPC dialog opens as a local screen panel near the bottom of the screen. The panel uses `textures/gui/9slice_container_grey.png`, shows the NPC head avatar, renders the NPC name in the CKDM bold font, renders dialog body copy with the normal Minecraft font, and shows right-side action buttons for Talk, Buy, Gift, Work, Training, Friendly Battle, Retry Battle, Tech License, and Bye only after that NPC has a valid home. Unhoused NPCs show only a close button while asking for housing or a rent contract. Training appears only when the NPC has a valid `class` id. Tech License appears for configured tech experts after the server shipping threshold is reached and does not require a ready workplace, so the license quest can unlock the gated tech needed for later setup. Friendly Battle appears for resident NPCs with a configured Pokemon roster and a ready workplace. Retry Battle appears when the player has an active lost/retryable NPC Pokemon battle or sparring quest for that NPC; right-clicking no longer auto-starts the retry, so stores, Talk, gifts, and work remain accessible. NPC nameplates with a valid `class` id render that class icon before the NPC name, and configured tech experts prepend their license `iconItem` before any class icon. The panel enters with a bottom-anchored scale/slide animation, the dialog body types in, and the vanilla hotbar is hidden while the dialog screen is open.

Buy opens the store template configured by the NPC `store` field and is shown only when a store is configured and any matching tech-license shop gate is satisfied. NPC stores share the same store TOML pool by store id, but each NPC uses its own `npc_<id>` stock key, so two NPCs can use `store = "cosmetics"` while rolling and depleting different daily/weekly stock. WORK grants a job application if the NPC has no workplace, or opens workplace management with MOVE and FIRE if one is assigned. MOVE gives a fresh job application for reassignment. FIRE clears the assigned workplace and leaves the NPC unemployed until WORK is used again. If configured `work_blocks` are missing around the workplace, assignment fails or the shop stays closed, and the NPC opens a close-only missing-block dialog. Tech License starts or advances that NPC's non-expiring tech questline from `tech_licenses/licenses.toml`; default licenses cost 10,000 chowcoins after their fetch/task proof steps, and certification intentionally bypasses workplace checks. Training requires the NPC to have an assigned workplace, then starts or advances the non-expiring mentor questline for the NPC's configured class. Mentor questlines live in class TOML under `[mentor_quest]`, reuse fetch/task/timed/Farmer's Delight food-chain hooks, charge the new unlock fee at the payment step, and end with the existing 1v1 NPC bossfight as a mentor duel. Winning the mentor duel unlocks and activates the class, grants class starting items, sends a title line, and broadcasts the unlock. If the payment step reaches a missing prerequisite/license condition, the failed Training reply can still show CHANGE with a chowcoin icon. CHANGE opens a selection list of owned same-type classes to replace; starter changes cost 50,000 chowcoins and upgrade changes cost 100,000 chowcoins. Replacing a starter class warns when upgrade classes would lose all starter prerequisites, and confirming removes those invalid upgrades too. Other failures stay close-only with the missing workplace, license, or prerequisite conditions. Full class questline content is documented in [NPC Class Quests](NPC_CLASS_QUESTS.md). After a successful NPC store purchase, the owning NPC opens a close-only follow-up dialog chosen from `shop_messages` by friendship category and quantity bucket. Gift is disabled unless the player is holding an item, shows a hover hint, consumes one held item on success, and plays a reaction dialog with a single OKAY close button. Locked relic/player-locked items are rejected by the existing relic transfer guard. Bye closes the normal screen.

Explorer compass stores are dynamic store offers, not a separate UI. Generator offers with `offer_type = "explorer_compass"` declare `target_type = "biome"` or `"structure"`, `dimension = "minecraft:overworld"` or another world id, and `distance_bands = ["near", "far", "very_far"]`; store refresh expands them into category offers from live biome/structure registry tags. Tag paths normalize into categories such as `forest`, `swamp`, `village`, or `ocean_explorer_maps`, with id-path fallback only when a target has no usable tags. Daily pools should usually use `near`/`far`; weekly pools can include `very_far`. A bought CKDM compass cancels the native Nature's/Explorer's Compass right-click GUI, searches the configured dimension from world origin instead of the player's current world, and claims one exact x/z target globally. Failed searches remove the compass, refund its stored chowcoin price, restore store stock, and do not change discovery state. Global claims are stored at `<world>/data/gisketchs_chowkingdom_mod/explorer_compasses/state.json` as exact target-location keys.

Debug command: `/npc work toggle` flips an in-memory workplace bypass. It defaults off each server/client restart. While enabled, NPC shop and Training skip workplace/work-block requirements, and NPC dialog names show `WORK OFF NPC` as a visible debug marker.

`settings.toml` top-level `protect_npcs_during_pokemon_battles = true` makes CKDM NPCs invulnerable while they are locked in RCT Pokemon battles. This covers gym trainers plus resident NPC Friendly Battle and `pokemon_battle` quest fights, and prevents normal world damage/death during the battle lock.

Talk opens a text input inside the existing dialog. Submitting sends the message to the server, validates NPC distance/state, shows a pending `...` in the dialog and nearby balloon while waiting, then replaces it with the validated LLM reply or a configured fallback. NPC talk input, dialog replies, and world-chat NPC replies allow up to 2,000 characters. LLM provider settings are global in NPC `settings.toml`; `llm_message_usage` controls which message surfaces may use LLM text. V1 supports `openai_compatible` and `gemini` provider modes and expects JSON output with `message`, optional `emote`, and optional memory/surface fields. The `emote` value is validated against `emotes.toml`; invalid, unavailable, or wrong-surface emotes become `none`, and sitting/lying emotes are not offered for normal talk. When no explicit emote is requested, CKDM tries `default_speaking_emote` for dialog, group talk, world chat, and micro-interaction speech. When `llm_streaming = true`, OpenAI-compatible dialog replies stream only when the current surface has no resolved emote choices; emote-aware replies use final JSON. NPC state stores per-player recognition as `recognized=true/false`. When recognition is false and LLM is enabled, the interaction forces a first-meeting prompt even if that surface's normal LLM usage flag is off: the NPC gives an arrival intro based on personality/lore, asks who the player is, acknowledges the player name, and asks for a home/rent contract when still unhoused.

Normal right-click greetings after first meeting use the NPC interaction director when `[interaction_director].enabled = true`. CKDM first selects a weighted eligible topic from real facts, then injects that topic as the LLM interaction focus; the LLM writes voice only. If the NPC is currently doing an ambient action, that ambient action preempts the weighted picker and records a topic like `ambient_work`, `ambient_pokemon`, or `ambient_missing_workplace`, so the reply explains what the NPC is doing. When an authored ambient balloon was visible, its exact line is passed into the LLM prompt and non-LLM fallback, so right-click follow-up can reference what the player just saw. Built-in topic buckets cover recent boss/global events, NPC hurt history, player held items/equipment/health/job, Pokemon party or nearby Pokemon, nearby NPCs, weather/time/location, memories, and filler personality lines. Recent selected topic ids are stored per NPC/player in NPC state, so repeats are suppressed by `topic_cooldown_minutes` and `recent_history_size`. `settings.toml` can add global `topics`, and each NPC config can add `[interaction_director]` `weight_overrides` or custom `topics` without replacing the built-ins.

Workplace dialogs can use LLM text when `llm_message_usage.work_application`, `work_manage`, `work_move`, `work_fire`, `assigned_workplace`, or `work_missing_blocks` are enabled. Prompt text for those surfaces lives under `[work]` in NPC `settings.toml`. Missing work-block prompts receive `{missing}`, `{requirements}`, `{workplace}`, and `{action}`. Class training dialogs can use LLM text when `llm_message_usage.class_training` is enabled. Mentor quest prompts include the full questline, current step, current progress, timed window when present, class classification, unlock cost, mentor NPC, and step flavor prompt. Training prompts under `[training]` still handle missing workplace, failed prerequisite/license conditions, and paid class changes; failed prompts receive `{player}`, `{npc}`, `{class}`, `{conditions}`, `{overall_level}`, `{cost}`, `{classification}`, `{change_options}`, and `{lost_classes}`. Missing-workplace training uses `workplace_required_llm_prompt`. Paid change dialogs use `change_offer_message`, `change_select_message`, `change_success_message`, `change_failed_funds_message`, `change_invalid_message`, and `change_lost_upgrades_warning`; `change_offer_llm_prompt` lets NPCs pitch the target class with playful rivalry toward replaceable classes.

The dialog header shows the NPC avatar, name, and a 10-icon friendship row. Positive levels render filled hearts, negative levels render angry icons, and remaining slots render empty hearts. Dialog body text uses the vanilla Minecraft font at 100% white opacity. Player names, mission/reward tags, and `<b>...</b>` spans render with the CKDM small bold font; `<b>...</b>` uses 100% white for short highlights such as numbers, item names, costs, requirements, places, and action words. Non-dialog outputs strip those tags.

The inventory side menu has a Friends screen listing configured NPCs. Each row shows the NPC head, name, friendship hearts, friendship level, and mission summary. Hovering a row shows friendship points, gift availability for the current reset period, shop open/close timing, and active or available mission progress.

In multiplayer, the interacting player sees the dialog screen only. Other players within 30 blocks of the NPC see the overheard NPC line as a world-space balloon above the NPC. Discord receives every NPC dialog through the webhook, with the NPC name/avatar as the webhook identity and a linked Discord user mention when the interacting player has an account link.

While talking, the NPC briefly stops navigating and looks at the interacting player. During the local dialog typewriter reveal, NPCs play proximity-faded animalese sounds using the configured voice pitch. If the resolved emote is marked `loop_while_talking`, the server replays that PlayerAnimator clip while the dialog keepalive keeps the NPC in its talking state, so the default speaking emote stays visually in sync with animalese/typewriter delivery. Nearby NPC balloons and hurt balloons do not play animalese.

NPCs no longer auto-greet players for first daily contact. Entering the global NPC greeting radius stays silent: no greeting balloon, no pause, and no forced attention. Right-clicking the NPC for the first chat of the in-game day still grants +25 friendship and uses the configured first-chat message pool when the NPC already has a home and is awake.

Unhoused move-in NPCs use camper housing balloons instead of daily greeting balloons until they have a home or a rent contract has been issued. While a player is in greeting radius, the needs-house or lost-house balloon refreshes in world-space so it remains visible and is not replaced by normal daily greetings.

## Definition Fields

Each NPC is one TOML file:

- `id`: Stable NPC id. Used for state, commands, contracts, and future relationship data.
- `name`: Display name.
- `title`: Display subtitle.
- `skin`: Future skin resource id. Current renderer uses `textures/entity/npc/finn.png` for `finn` and a vanilla fallback for other NPC ids.
- `body_type`: Player-model shape. Supported values: `normal`, `slim`.
- `body_model`: Female Gender-style body overlay. Supported values: `boy`, `girl`. This is separate from `body_type`; `body_type` controls arm width, `body_model` controls the CKDM NPC body overlay.
- `fg_bust_size`: Female Gender bust size for NPCs. Default `0.6`, clamped from `0.0` to `0.8`.
- `fg_bounce`: Female Gender motion multiplier for NPCs. Default `0.333`, clamped from `0.0` to `0.5`.
- `fg_floppy`: Female Gender motion multiplier for NPCs. Default `0.75`, clamped from `0.25` to `1.0`.
- `height`: Pehkui height scale. Default `1.0`, clamped from `0.6` to `1.4`.
- `weight`: Pehkui width scale. Default `1.0`, clamped from `0.6` to `1.4`.
- Female Gender Mod NPC visuals require the client mod. CKDM checks for `wildfire_gender`, then renders its own Female Gender-style wedge bust layer on normal/playerlike NPC renderers using the NPC skin texture. Gecko custom-animation NPCs do not use this layer.
- Saved configured NPCs refresh `body_type`, `body_model`, Female Gender values, renderer mode, name, and Pehkui body scale from TOML when loaded, so old spawned NPCs pick up visual config changes after a world reload.
- `custom_animation`: Uses the GeckoLib playerlike NPC renderer instead of the EMF-compatible renderer path. Default `false`.
- `playerlike_animation`: Uses the Better Combat/Mob Player Animator-compatible playerlike renderer instead of Gecko custom animation. Default `false`.
- `main_pokemon`: Optional Cobblemon species id, for example `main_pokemon = "cobblemon:growlithe"`. When Cobblemon is installed, CKDM can release one tagged NPC-owned companion near the NPC during configured event windows. The companion follows the NPC, is not catchable, is not battleable, and is included in LLM prompt context so the NPC can sometimes talk about it.
  By default NPC-owned Pokemon are recalled during sleep, home, and work, then released during `pokemon_roam`, some `meetup`, and Pokemon-focused ambient events.
- `class`: Optional class id for Training dialog classification, for example `class = "rogue"`. Training starts the matching class mentor questline from that class TOML and unlocks the class only after payment and mentor duel victory.
- `store`: Store template id, for example `store = "cosmetics"`. This selects the store TOML/pool only; runtime stock remains per NPC through `npc_<id>`.
- `job_definition`: Static work behavior knobs loaded from TOML: `scan_interval_ticks`, `roam_radius`, and `work_scan_radius`. Legacy `job_definition.store` is still accepted as a fallback for older configs, but new configs should use top-level `store`.
- `schedule`: Static day schedule loaded from TOML. Each activity entry has `from_hour`, `to_hour`, and `activity`. Hours use a 24-hour clock where `00` is midnight.
- `personality`: Future dialog/LLM seed fields: `llm_prompt`, `traits`, `speech_style`, `catchphrases`.
- `boss`: Optional boss duel knobs: `enabled`, `health`, `damage`, `template`, `main_hand`, `off_hand`, and nested `boss.balloons`. `main_hand` and `off_hand` are item ids equipped only during the boss fight; `none`/`empty`/`air` leaves that hand empty, blank or invalid `main_hand` falls back to a template-appropriate item (`minecraft:iron_sword` by default, bow/crossbow for archer-style templates, empty hands for empty-hand casters), and blank `off_hand` leaves that hand empty. Effective boss HP is doubled at fight start. Defaults allow OP-only `/npc fight` testing on any NPC with the current `sword_user` template. Boss movesets can also define `phases` with health thresholds, damage/speed multipliers, offense-chain tuning, transition dialogue prompts/fallbacks, sound-event music hooks, `hover_height` for floating casters, and anti-spam guard knobs such as `attack_phase_damage_multiplier`, `attack_windup_damage_multiplier`, `attack_active_damage_multiplier`, `attack_late_damage_multiplier`, `attack_windup_pressure_multiplier`, `attack_active_pressure_multiplier`, `anti_spam_pressure_threshold`, and `anti_spam_reactive_guard_cooldown_ticks`. Boss moves support `melee`, `area`, `roll`, `dodge`, `projectile`, `beam`, and `support`; projectile moves use PlayerAnimator draw/release clips and support `projectile_type = "arrow"` real vanilla arrows or `projectile_type = "magic"` server-ticked particle projectiles with speed, impact radius, particles, optional status effect, damage, and phase-gate fields. Beam moves trace line of sight, draw visible line VFX, and can repeat small hits during one channel. Area moves can respect `arc_degrees`, apply status/fire through `status_effect_*` and `fire_ticks`, and create temporary ground hazards through `hazard_*` fields. Caster/support moves can reuse registry-backed particle and sound ids through `projectile_particle`, `cast_particle`, `release_particle`, `impact_particle`, `support_particle`, `cast_sound_id`, `release_sound_id`, and `impact_sound_id`; missing mod ids fall back safely. Support moves can heal or add temporary virtual absorption through `self_heal_*` and `absorption_*` fields. Boss balloons are data-driven short world-space lines for `chase`, `attack`, `recovery`, `taunt`/guard bait, `guard_react`, `parry`, `hit_player`, `took_damage`, `victory`, and `defeat`. Supported placeholders: `{player}`, `{npc}`, `{boss}`, `{phase}`, `{health}`, and `{max_health}`.
- Boss `template` may stay at default `sword_user` or name a real moveset id such as `bounty_hunter` or `forcemaster`; class id remains the fallback moveset selector.
- `housing`: Move-in rules: `can_move_in`, `requires_bed`.
- `voice`: Animalese dialog voice settings: `animalese_pitch` (`high`, `med`, `low`, `lowest`), `pitch`, `volume`, and `radius`.
- `chat`: World chat settings. `call_names` are names/nicknames that can address the NPC from Minecraft or Discord chat; `minecraft_chat` and `discord_chat` toggle those channels.
- `gifts`: Gift ids/tags grouped by `loved`, `liked`, `disliked`, plus `daily_limit`, `reset_hour`, `llm_sentiment_prompt`, and reaction message pools for `loved`, `liked`, `disliked`, and `neutral`. Configured gift ids/tags decide friendship deterministically. If a player gives an unlisted item and gift LLM is enabled, the NPC asks the LLM for JSON `{ "message": "...", "gift_sentiment": "loved|liked|neutral|disliked" }`; invalid or failed sentiment falls back to `neutral`. `gifts.outgoing` configures NPC-to-player daily gifts with `enabled`, `radius`, `min_friendship_level`, `rare_friendship_level`, `follow_seconds`, `offer_messages`, `fallback_messages`, `llm_enabled`, `llm_prompt`, weighted `pool`, weighted `rare_pool`, and additive `extra_pool` / `extra_rare_pool` lists. Each pool entry has `item`, `qty`, and `weight`.
- `missions`: NPC quest settings. `enabled`, `offer_radius`, `offer_balloon_messages`, and legacy/custom `pool` entries. Each mission has `id`, `category` (`task`, `timed`, `fetch`, `quiz`, `food_chain`, `pokemon_battle`, or `sparring`), `event`, `event_desc`, `quest_text`, `pass_id`, `xp`, optional `chowcoins`, `goal`, optional `time_window_seconds`, `fetch_item`, `fetch_count`, optional `filters`, `weight`, and optional message pools.
- Global quest pace lives in `settings.toml` under `[quests]`. `max_daily_quests = 5` caps used NPC quest slots per reset period; active and completed quests both count.
- `unique_quests`: Per-NPC quest template pools with the same shape as `generic_quests.toml`. These compile into `missions.pool` at reload time. Supported pools are `fetch`, `kill`, `timed`, `travel`, `craft`, `smelt`, `eat`, `quiz`, `catch_pokemon`, `quality_food_fetch`, `quality_crop_fetch`, and `food_chain_quest`.
- `friendship_messages`: Optional per-NPC category message additions for `interact`, `gift`, `hurt`, `wake`, `greeting`, and `first_daily_chat`. Categories are `hatred`, `enemy`, `dislike`, `neutral`, `okay`, `good_friends`, and `best_friends`. Message pools resolve in order: built-in defaults, shared `friendship_messages.toml`, then NPC-specific `friendship_messages`. Each configured layer joins the inherited pool and is inserted twice, so local additions have roughly 2:1 weight against inherited generic lines.
- `shop_messages`: Optional post-purchase message overrides with `single`, `normal`, and `bulk` buckets. Each bucket has the same friendship categories as `friendship_messages`. Supports `{player}`, `{npc}`, `{item}`, `{quantity}`, `{total}`, `{friendship_level}`, and `{friendship_points}`.
- `camper_messages`: Optional uncategorized camp message pools: `needs_house_balloon`, `needs_house_dialog`, `lost_house_balloon`, and `lost_house_dialog`. Supports `{player}` and `{npc}`.
- `hurt_messages`: NPC speech pool used every third hit from the same player. Supports `{player}`.
- `wake_messages`: NPC speech pool used when a sleeping NPC is right-clicked. Supports `{player}`.
- `npc_interaction_messages`: Optional NPC-specific world-space lines for NPC-to-NPC micro interactions. Supports `{npc}` and `{other}` and is added on top of shared generic interaction lines.
- `work_blocks`: Required nearby blocks/entities for workplace assignment and selling. Each entry has `id`, `count`, and optional `display_name`. Block ids, block tags like `#minecraft:beds`, entity ids like `minecraft:item_frame`, and entity tags are supported. Counts are scanned around the workplace using `job_definition.work_scan_radius`; bed halves count as one bed.

Shared generic messages live in `friendship_messages.toml` and affect every NPC. NPC TOML files can add their own message lines under the same keys; those NPC-specific lines join the shared random pool with higher weight.

Global greeting behavior lives in `settings.toml`:

```toml
[greetings]
radius = 5.0
cooldown_seconds = 60
balloon_duration_seconds = 5
```

Clock compatibility is automatic when Better Days is installed and `betterdays-common.toml` exists. Chow Kingdom reads Better Days `dayStart` and `nightStart`, then maps that raw sky-time range to a 24-hour clock:

```toml
speedMethod = "MINUTES"
daySpeedMinutes = 0.5
nightSpeedMinutes = 0.5
dayStart = 23500.0
nightStart = 12500.0
```

If Better Days is not present, Chow Kingdom falls back to vanilla `0..24000` timing. NPC schedules, first daily chats, gift reset periods, respawn hour checks, camper cooldown hours, shipping bin payout, and LLM time context all use this clock. NPC LLM world context also includes Serene Seasons season and season day when that mod is loaded. Store stock resets intentionally still use real-life time from store config.

Camper rotation settings also live in NPC `settings.toml`:

```toml
[campers]
cooldown_min_hours = 24
cooldown_max_hours = 48
needs_house_llm_prompt = "The player found you waiting at camp without a home. Ask for a bed or small house and mention the rent contract."
lost_house_llm_prompt = "Your assigned bed or home was removed. Tell the player you lost your bed and need a new one."

[llm_message_usage]
camper_needs_house = false
camper_lost_house = false
work_missing_blocks = true

[work]
missing_work_blocks_llm_prompt = "The player tried to use your workplace, but required work blocks are missing nearby. Missing: {missing}. Full requirement: {requirements}. Workplace: {workplace}. Reply in character and tell them what to add before you can work."
```

Example workplace requirements:

```toml
work_blocks = [
	{ id = "minecraft:barrel", count = 3, display_name = "barrels" },
	{ id = "minecraft:item_frame", count = 2, display_name = "item frames" },
	{ id = "#minecraft:beds", count = 1, display_name = "bed" },
]
```

NPC quest behavior:

- NPC quests live in each NPC TOML under `[missions]` / `missions.pool`.
- Shared generic quests live in `generic_quests.toml`. These are auto-added to every NPC with questing enabled.
- Per-NPC template quests live under `[unique_quests]` in that NPC's TOML and are added on top of generic and legacy `missions.pool` entries.
- Quests are offered only while that NPC's schedule activity is `meetup`. Quest reset uses the earliest configured `meetup` start hour, so keep all NPCs on the same meetup window when they should gather together.
- New quest offers require the NPC to have a valid home bed and an assigned workplace. Active quest rewards can still be claimed if the NPC later loses either assignment.
- A player can use at most `quests.max_daily_quests` NPC quest slots per reset period, default 5. Active quests plus completed NPC ids count toward this cap, so completing one quest does not free another same-day slot.
- Declining an NPC quest suppresses that NPC's offer for 1 in-game hour; the same daily offer remains until the next meetup reset.
- Task quests progress from existing battlepass mission signals such as `minecraft:monster_killed` or `cobblemon:pokemon_caught`.
- Timed quests progress from matching battlepass mission signals inside a rolling `time_window_seconds` window. Each matching signal adds one timestamp. Old timestamps fall out of the window until the goal is reached; once reached, the quest is locked complete for claim.
- Fetch quests complete when the player returns to the NPC with the configured item; the required item count is consumed on reward claim.
- Food-chain quests require the player to create the configured Farmer's Delight food after accepting the quest, then return that marked created stack to the NPC. Premade food in inventory does not satisfy the creation step and is not consumed for the hand-in.
- Pokemon battle quests use `category = "pokemon_battle"`. Accepting consumes one NPC quest slot, moves the player and resident NPC to `main_stadium`, samples 6 Pokemon from that NPC's 15-Pokemon roster in `config/gisketchs_chowkingdom_mod/npc_battles/rosters/<npc_id>.json`, and starts an RCT `GEN_9_SINGLES` battle. Winning completes the quest and grants combat BP XP/Chowcoins; losing leaves the quest active so the player can retry before reset from the normal NPC dialog `RETRY BATTLE` button. Friendly Battle uses the same roster/stadium flow but does not consume a quest slot and grants no reward.
- Sparring quests use `category = "sparring"` and are allowed only for NPCs with a non-empty `class`. They start the existing CKDM NPC boss duel in sparring mode with 45% HP and 35% damage, no class unlock, no mentor progression, no payment, and no item rewards. Winning completes the quest; losing leaves it active for retry.
- Pokemon battle, Friendly Battle, and sparring require an assigned workplace plus all configured `work_blocks`. This gate is independent of current work hour.
- Rewards grant configured battlepass XP to `pass_id` and optional `chowcoins` immediately. Unclaimed completed NPC quests expire at the next meetup reset.
- Quest offer selection is deterministic per NPC/player/reset period and honors `weight`; higher weight means more likely.

NPC quest debug tester:

- `/quests debug` prints command usage. Requires permission level 2.
- Debug quests are inserted into the executing player's tracked NPC quest list under `Quest Debug`. They use the same NPC quest state, HUD sync, fetch inventory counting, and battlepass signal matching as real NPC quests.
- Debug quests have `0` XP and `0` chowcoins. They are for progress testing, not rewards.
- `/quests debug clear` removes only debug quests. Real NPC quests are left alone.
- `/quests debug fetch <item> <qty>` tracks an exact-item fetch, for example `/quests debug fetch minecraft:apple 8`.
- `/quests debug kill <entity> <qty> [dimension]` tracks entity kills, for example `/quests debug kill minecraft:skeleton 30 minecraft:overworld`.
- `/quests debug timed_kill <entity> <qty> <seconds> [dimension]` tracks kills inside a rolling time window, for example `/quests debug timed_kill minecraft:zombie 3 5 minecraft:overworld`.
- `/quests debug travel on_foot <blocks>` tracks foot-only travel, for example `/quests debug travel on_foot 500`.
- `/quests debug travel pokemon_land <blocks>` and `/quests debug travel pokemon_flying <blocks>` track Cobblemon mount travel.
- `/quests debug craft <item> <qty>`, `/quests debug smelt <item> <qty>`, and `/quests debug eat <item> <qty>` track item task signals.
- `/quests debug catch any <qty>`, `/quests debug catch type <type> <qty>`, `/quests debug catch species <species> <qty>`, and `/quests debug catch category <legendary|mythical|starter> <qty>` track Cobblemon catch quests.
- `/quests debug quiz [xp chowcoins topic]` starts an LLM quiz from the NPC under crosshair, for example `/quests debug quiz 100 50 town lore and recent events`.
- `/quests debug quality_food <tier> <qty>` and `/quests debug quality_crop <tier> <qty>` track Quality Food fetches. Tiers are `iron`, `gold`, and `diamond`.
- `/quests debug food_chain <farmersdelight:item> <qty> [cook|craft|smelt|any]` tracks Farmer's Delight food created after accepting, for example `/quests debug food_chain farmersdelight:beef_stew 1 cook`.
- `/quests debug custom <event> <qty> [key=value key=value]` tracks any task event/filter pair the mission event bank understands, for example `/quests debug custom minecraft:entity_killed 5 entity=minecraft:zombie dimension=minecraft:overworld`.
- Quiz quests are single-attempt. A correct answer completes the quest and grants rewards; a wrong answer fails and removes the quest with no reward.
- `/npc debug quest_roll` rolls a random real quest from the NPC under the admin player's crosshair, shows the normal quest balloon/dialog, and stores that rolled offer for the next `ACCEPT`. Running it again rerolls. It bypasses meetup, daily slot, existing active/completed state, and work-block gates for admin testing only.

Example NPC quest config:

```toml
[missions]
enabled = true
offer_radius = 7.0
offer_balloon_messages = ["@quest_log.png {quest_text}"]
pool = [
  { id = "hunt_mobs", category = "timed", event = "minecraft:monster_killed", event_desc = "Defeat {goal} Monsters In {seconds}s", quest_text = "Thin out a quick monster wave near town.", pass_id = "combat", xp = 100, chowcoins = 50, goal = 3, time_window_seconds = 20 },
  { id = "fetch_beef", category = "fetch", event_desc = "Bring {goal} Cooked Beef", quest_text = "Bring food for patrol.", pass_id = "cozy", xp = 80, chowcoins = 25, fetch_item = "minecraft:cooked_beef", fetch_count = 4 },
]
```

Example shared `generic_quests.toml`:

```toml
enabled = true

[fetch]
pool = [
  { item = "minecraft:apple", qty = 8, xp = 70, chowcoins = 25, weight = 10, quest_text = "Bring fresh apples for town supplies." },
]

[timed.easy_mobs]
pool = [
  { entity = "minecraft:zombie", qty = 4, time_window_seconds = 25, dimension = "minecraft:overworld", xp = 100, chowcoins = 50, weight = 10, quest_text = "Thin out a fast wave of zombies before they wander into town." },
]

[travel]
pool = [
  { mode = "on_foot", qty = 1000, xp = 80, chowcoins = 25, weight = 10, quest_text = "Scout the roads on foot. No mounts, no flying." },
  { mode = "pokemon_land", qty = 1500, xp = 90, chowcoins = 35, weight = 7, quest_text = "Ride a land Pokemon along the routes and check the roads." },
  { mode = "pokemon_flying", qty = 2000, xp = 110, chowcoins = 50, weight = 5, quest_text = "Fly on a Pokemon and survey the town from above." },
]

[craft]
pool = [
  { item = "minecraft:torch", qty = 32, xp = 80, chowcoins = 25, weight = 8, quest_text = "Craft torches for safer routes." },
]

[smelt]
pool = [
  { item = "minecraft:iron_ingot", qty = 8, xp = 90, chowcoins = 40, weight = 8, quest_text = "Smelt iron for town maintenance." },
]

[eat]
pool = [
  { item = "minecraft:bread", qty = 3, xp = 60, chowcoins = 20, weight = 8, quest_text = "Eat proper food before more work stacks up." },
]

[quiz]
pool = [
  { quiz_topic = "town lore", xp = 45, chowcoins = 25, weight = 4, quest_text = "Answer a quick town lore question." },
  { quiz_topic = "recent events", quiz_prompt = "Ask about one concrete recent global event, if any exists.", xp = 50, chowcoins = 35, weight = 4, quest_text = "Answer a question about what has been happening lately." },
]

[catch_pokemon]
pool = [
  { pokemon_type = "water", qty = 3, xp = 110, chowcoins = 50, weight = 6, quest_text = "Catch Water Pokemon for field support." },
  { category = "starter", qty = 1, xp = 140, chowcoins = 75, weight = 2, quest_text = "Catch a starter Pokemon for special records." },
]

[quality_food_fetch]
pool = [
  { quality_tier = "iron", qty = 4, xp = 100, chowcoins = 50, weight = 6, quest_text = "Bring iron quality food for town meals." },
  { quality_tier = "gold", qty = 3, xp = 130, chowcoins = 75, weight = 4, quest_text = "Bring gold quality food for a special request." },
]

[quality_crop_fetch]
pool = [
  { quality_tier = "iron", qty = 6, xp = 100, chowcoins = 50, weight = 6, quest_text = "Bring iron quality crops for town stores." },
  { quality_tier = "diamond", qty = 2, xp = 170, chowcoins = 110, weight = 2, quest_text = "Bring diamond quality crops for a rare order." },
]

[food_chain_quest]
pool = [
  { item = "farmersdelight:beef_stew", process = "cook", qty = 1, xp = 130, chowcoins = 70, weight = 5, quest_text = "Cook beef stew after accepting this order, then bring it back." },
  { item = "farmersdelight:mixed_salad", process = "craft", qty = 1, xp = 100, chowcoins = 45, weight = 5, quest_text = "Prepare a fresh mixed salad after accepting this order, then bring it back." },
]
```

Example per-NPC unique quest template:

```toml
[unique_quests.timed.easy_mobs]
pool = [
  { entity = "minecraft:skeleton", qty = 4, time_window_seconds = 25, dimension = "minecraft:overworld", xp = 130, chowcoins = 70, weight = 8, quest_text = "The archers are testing the walls. Break their line before it reforms." },
]

[unique_quests.fetch]
pool = [
  { item = "minecraft:arrow", qty = 16, xp = 80, chowcoins = 25, weight = 10, quest_text = "Bring arrows for training drills." },
]

[unique_quests.food_chain_quest]
pool = [
  { item = "farmersdelight:vegetable_soup", process = "cook", qty = 1, xp = 120, chowcoins = 60, weight = 6, quest_text = "Cook vegetable soup after I ask, then bring the fresh bowl back." },
]
```

Template quest fields:

- `item`: Exact item id for `fetch`, `craft`, `smelt`, and `eat`.
- `entity`: Exact entity id for `kill`.
- `time_window_seconds`: Rolling window for `timed` quests. Timed kill pools use this with `qty`, for example 3 kills in 5 seconds.
- `mode`: Travel mode. Supported values are `on_foot`, `pokemon_land`, and `pokemon_flying`.
- `quiz_topic`: Quiz topic for `quiz` pools. The LLM uses this with NPC lore, world context, recent events, and player memories.
- `quiz_prompt`: Optional extra quiz instruction for `quiz` pools. The generated JSON must contain `message`, `choices`, and `answer`.
- `pokemon_type`: Pokemon type for `catch_pokemon`, such as `water`, `fire`, or `grass`.
- `species`: Exact Cobblemon species id for `catch_pokemon`, such as `cobblemon:pikachu`.
- `category`: Pokemon category for `catch_pokemon`; supported values are `legendary`, `mythical`, and `starter`.
- `label`: Cobblemon label/form filter for advanced catch quests.
- `quality_tier`: Quality Food tier for `quality_food_fetch` and `quality_crop_fetch`; supported values are `iron`, `gold`, and `diamond`.
- `quality_level`: Numeric Quality Food level for quality fetch pools; `1` iron, `2` gold, `3` diamond.
- `process`: Creation process for `food_chain_quest`. Supported values are `cook`, `craft`, `smelt`, and `any`. Cooking pot outputs are treated as `cook` when NeoForge reports them through the crafted item event.
- `qty`: Required amount.
- `dimension`: Optional dimension filter, such as `minecraft:overworld` or `minecraft:the_nether`.
- `pass_id`, `xp`, `chowcoins`, `weight`, `quest_text`, `event_desc`, and message pools work like legacy `missions.pool`.
- `filters`: Optional extra event filters for advanced signals.

NPC-to-NPC micro interaction settings live in NPC `settings.toml`:

```toml
[npc_interactions]
enabled = true
radius = 7.0
duration_seconds = 30
cooldown_min_hours = 1
cooldown_max_hours = 2
witness_required = true
witness_radius = 8.0
first_witness_nudge_min_seconds = 5
first_witness_nudge_max_seconds = 15
area_cooldown_min_seconds = 60
area_cooldown_max_seconds = 90
daily_participation_budget = 7
trainer_daily_participation_budget = 7
pair_cooldown_hours = 6
balloon_refresh_seconds = 6
messages = ["Talking with {other}...", "Catching up with {other}."]

[pokemon_companions]
event_windows = true
release_min_seconds = 180
release_max_seconds = 420
recall_min_seconds = 120
recall_max_seconds = 300
pokemon_roam_release_chance = 70
meetup_release_chance = 35
ambient_pokemon_release_chance = 60
recall_during_sleep = true
recall_during_home = true
recall_during_work = true
```

Example shared `friendship_messages.toml` addition:

```toml
[greeting]
neutral = ["Hi, {player}!"]
good_friends = ["Good to see you, {player}."]

[first_daily_chat]
neutral = ["First check-in of the day, {player}."]
```

Example NPC-specific addition inside an NPC TOML file:

```json
{
	"friendship_messages": {
		"greeting": {
			"neutral": ["Adventure time, {player}?"]
		},
		"first_daily_chat": {
			"neutral": ["First quest check-in today. What are we doing, {player}?"]
		}
	}
}
```

## State

Runtime state is stored in world data:

`<world>/data/gisketchs_chowkingdom_mod/npcs/state.json`

Resident state tracks each NPC's entity UUID, camp position, assigned home bed, assigned workplace block, whether the NPC was fired from work, whether a contract has been given, per-player recognition flags, recent hurt records, per-player conversation history, per-player gift counters, last hurt player/streak, death state, scheduled respawn day, and camper return reason. World state also tracks the latest camping block position, the active unhoused camper id, and the next camper cooldown tick. Hurt history stores only every third same-player hit event and keeps the latest 10 records. This is world data, separate from static JSON definitions and runtime brain state.

Greeting state is tracked per NPC/player. It stores legacy auto-greeting fields plus the first-chat day used to prevent duplicate daily friendship rewards.

NPC quest state is tracked per player. It stores the active meetup reset period, accepted NPC quests with their next meetup expiry tick, completed NPC ids for that period, and per-NPC decline cooldown ticks. When the period changes, active unclaimed NPC quests expire only after their stored expiry tick.

Outgoing NPC gift state is tracked per NPC/player. It stores the current scheduled gift day/hour and the last offered day, so a ready gift attempt either delivers or times out, then cools down until the next in-game day.

Per NPC/player friendship is stored as points from `-1000` to `1000`, defaulting to `100` (`Lv. 1`). The derived friendship level is `-10` to `10`. Categories map as: `-10 hatred`, `-9..-6 enemy`, `-5..-3 dislike`, `-2..2 neutral`, `3..5 okay`, `6..9 good_friends`, `10 best_friends`.

Each NPC/player conversation keeps at most 30 records. The world also keeps at most 30 global NPC-relevant events. This is the foundation for future LLM prompt context.

## Runtime Flow

- `NpcDefinition`: static NPC identity loaded from TOML.
- `NpcJobDefinition`: static work behavior values loaded from TOML.
- `NpcScheduleDefinition`: static 24-hour schedule loaded from TOML.
- `NpcResidentState`: saved world data for home bed, camp, contract, and spawned entity UUID.
- `ChowNpcEntity`: the Minecraft entity that owns the SBL brain, interacts, renders, and exposes debug state.
- `NpcSmartBrain`: SBL sensors, core tasks, and idle behavior ordering for town NPCs.
- `NpcSmartBrainOverrides`: SBL-backed temporary interrupt behavior state for reactive events, such as retaliation or running away from danger.

## Memory Context

NPC memory records the current player-specific conversation and a small global event stream. Current captured events:

- Player interacts with NPC.
- NPC dialog message.
- Player hurts NPC.
- NPC hurt response.
- NPC death and killer when known.
- NPC respawn.
- Player death messages.
- Player gives an item to an NPC.
- NPC gives a pending gift item to a player.
- NPCs pause to chat with each other in-world.

Future LLM prompt building should read `NpcStore.llmContext(npcId, player)` and inject global events before player-specific conversation records. The context can also carry the current 24-hour schedule hour plus Serene Seasons season/day when available.

The LLM context includes the current friendship snapshot: points, level, and category. Gift history is two-way: player-to-NPC gifts and NPC-to-player gifts are both saved into conversation history, and a short player memory is recorded for each gift direction. Treat this as high-priority tone context for future generated NPC replies.

Current built-in activities are `work`, `meetup`, `home`, and `sleep`. `meet up`, `meet_up`, `town_center`, `town plaza`, and `plaza` normalize to `meetup`. If an older NPC config has no schedule, the loader supplies the default `06-15 work`, `15-20 meetup`, `20-22 home`, `22-06 sleep` routine. During `sleep`, Finn walks to his assigned bed and uses the sleeping pose when close enough.

Right-clicking a sleeping NPC wakes it, opens wake-specific dialog, pauses sleep while talking, then lets the brain return it to sleep when the dialog window ends and the schedule is still `sleep`.

When the same player hits an NPC three times in a row, the NPC speaks a random `hurt_messages` line in a nearby world-space balloon. Those third-hit events are persisted in `NpcResidentState` with timestamp and player identity.

The third same-player hit also starts a short SBL override. Retaliation no longer uses the old Gecko `run_with_sword` / `attack_sword` path. If the NPC has a class or explicit boss template, the override resolves that boss moveset, equips the matching retaliation armory, and picks a random attack from its melee, area, projectile, or beam moves using PlayerAnimator/playerlike clips. Archer-style retaliation can fire real arrows; caster retaliation uses the moveset's spell hand animations and damage timing. NPCs without a class or explicit boss template use the lightweight fallback: chase the attacker and hit with an empty hand using a playerlike attack clip. Damage and knockback fire from the selected move hit tick only if the player is still in range, and the override restores the previous held items and renderer mode when finished. NPCs also run away for 3 seconds when they step on fire, soul fire, campfire, or soul campfire. Overrides pause normal schedule/job navigation and then fall back to the regular SBL idle brain.

OPs can run `/npc fight` while looking at any NPC to start a temporary non-lethal boss duel. The boss loop uses PlayerAnimator-only boss visuals from the moveset and stays offensive in every phase: rotating footwork, attack while moving, timed moving recovery, then back to offense. Effective virtual boss HP is doubled for normal and debug fights. Boss armory is configured per NPC with `[boss] main_hand` and `off_hand`, equips only during the duel, and restores previous held items after the fight. Warrior phase 1 is already aggressive with a readable 1-attack budget before recovery; at half virtual health, warrior enters phase 2, pauses combat, opens the NPC dialog screen with animalese voice, uses the configured LLM transition prompt with fallback, then resumes offense when the dialog closes. The phase transition line is not sent to world chat or boss balloons. Phase 2 moves faster, hits harder, and chains 3-5 attacks with shorter chain recovery. Rogue/Ezio uses the same two-phase template with dual-wield armory, slightly faster phase speed, lighter damage, and Spell Engine / Better Combat dual-handed PlayerAnimator attacks. Archer/Huntress Wizard uses the same phased boss shell with `archers:composite_longbow`, ranged spacing, `spell_engine:archery_pull` draw clips, `spell_engine:archery_release` release clips, and real vanilla arrow projectiles; phase 2 chains 2-3 shots and unlocks volley through move phase gating. Wizard/Gandalf uses the phased boss shell with `wizards:staff_wizard`, normal walking between casts, `spell_engine:one_handed_projectile_charge`/`release`, server-ticked magic projectiles for arcane, fire, and frost spells, and Spell Engine particle/sound ids for caster VFX when present; frostbolt applies brief Slowness I on hit. Water Wizard/Katara uses the `water_wizard` template with empty hands, flowing natural movement, water whip, splash, waterball, ice bind, capped springwater support, phase-2 hydro beam/avatar burst, heavy water/ice particles, no hover, and no teleport. Fire Wizard/Zuko uses the `fire_wizard` template with empty hands, aggressive running/side pressure, normal rolls/flame steps, fire punches, fire blasts, fireballs, scorch/sweep pressure, and phase-2 dragon breath, fire wall, and meteor hazards; it has no weapon and no teleport. Wind Wizard/Aang uses the `wind_wizard` template with empty hands, fast natural movement, air cutters, gust cones, spiral gust, phase-2 updraft/avatar pressure, normal air roll/step movement, heavy wind particles, no hover, and no teleport. Arcane Wizard/Invoker uses empty hands (`main_hand = "none"`), floats about 1 block above the fight plane, keeps ranged spacing, casts arcane bolt/blast/missile/beam, and uses blink teleport dodges plus arcane ward parries; it has no melee, staff, sword, or combat-roll move. Priest/Pope Leo uses the phased boss shell with `paladins:holy_staff`, holy shock projectile pressure, judgement burst close pressure, limited self-heal, and temporary virtual absorption. Priest VFX reuses Spell Engine healing clips plus Paladins particle/sound ids when loaded, with safe vanilla fallbacks. Bard/Venti uses its own archer-style boss moveset with `bards_rpg:aether_harp_crossbow`, Archer ranged spacing, real vanilla arrows, `bards_rpg:harp_channel`/`harp_release`, Bard spell ids, and music-note/star particles and Bard sounds. Its current kit is starshot, mocking shot, ballad shot, phase 2 crescendo volley, backstep, and side roll; no Bard boss move is melee, area, support, lute, or lyre in this version. Berserker/Zagreus uses `simplyswords:ribboncleaver`, slow heavy two-handed attacks, controlled-frenzy blood/rage VFX, and longer but bounded recovery windows; phase 2 chains 2-3 heavy attacks and unlocks rumbling swing plus nordic storm. Witcher/Geralt uses `witcher_rpg:steel_witcher_sword`, frequent Witcher sign casts, and fencing clips: fast attacks, strong attack, Aard cone knockback, Igni cone fire, Quen absorption, phase-2 Aard Sweep, Yrden ground hazard, Axii magic projectile/debuff, Rend, Whirl, and Reflexes. Ranged/caster bosses circle, retreat, and occasionally advance during attack windups instead of acting like turrets; melee bosses charge, angle-step, dash out, and re-engage. Recovery accepts safe punish hits until the move timer expires or `recovery_hits_allowed` is reached; warrior, rogue, archer, wizard, arcane_wizard, priest, bard, berserker, and witcher V1 use a 4-hit cap and per-attack windows. During active attack animations, duelist hits follow a timing curve: windup hits deal no virtual HP damage and build high pressure, active/release hits deal chip damage, and late attack hits deal partial damage; the boss animation and scheduled boss hit tick continue. During recovery, warrior uses `bettercombat:pose_one_handed_backwards`, faces the player, and passively strafes side to side at slower recovery speed. Rapid attack/recovery hits build anti-spam pressure, and damage_lockout_ticks blocks ultra-rapid repeat HP loss with parry VFX; if pressure or the recovery hit cap trips while the reactive cooldown is ready, the boss does one short parry/roll/dodge combo breaker and returns to offense. Extra swings after the recovery cap are blocked without converting the fight into a long defensive guard loop. Only Arcane Wizard's dodge is a blink teleport. The custom CKDM boss bar replaces the visible vanilla boss overlay: boss name uses CKDM bold font, HP uses the battlepass 9-slice progress textures, HP depletion lerps smoothly on the client, and the detail line shows the moveset phase plus live `NPC mode` such as `offense`, `attacking`, `recovery`, `dialogue`, `guard`, `parry`, `rolling`, or `dodging`. Boss moveset phases can define `music_id`, `music_volume`, `music_pitch`, and `music_repeat_ticks`; these are sound-event hooks only, so music assets are supplied separately. The current warrior, rogue, archer, wizard, water_wizard, fire_wizard, wind_wizard, arcane_wizard, priest, bard, berserker, and witcher configs reference Cataclysm music sound events for phase BGM. Boss music stops when the fight ends or is cancelled, when the player leaves/unloads the world, when another boss replaces the bar, or when boss-bar sync goes stale. Bossfight chat balloons are selected from each NPC's `[boss.balloons]` pools on landed boss hits, accepted recovery damage, reactive/legacy guard events, NPC victory, and player defeat. Each bark rolls a 30% chance so the NPC does not talk on every trigger. Player damage reduces virtual boss health during recovery and attack windows instead of killing the NPC; support absorption is consumed before HP. Any active bossfight lethal damage against the duelist, including lingering/entityless fire ticks from boss moves, skips revive, ends as an NPC victory, heals the player, and opens the close-only victory dialog. Boss duels are isolated 1v1s: third-party entity damage into either participant is blocked, and the duelist and boss cannot damage outside targets while the fight is active. Right-click NPC interaction is blocked during the fight, so dialogue, gifts, quests, and join-talk prompts cannot interrupt combat. The duelist sees no interaction warning; spectators get a snackbar if they try to interact. On boss defeat, the custom boss bar clears, the NPC restores its previous animation state and held items, and the NPC opens a close-only defeat dialog using LLM when enabled.

Bounty Hunter/Aloy uses the `bounty_hunter` boss template with `archers:aether_longbow`. It is ranged-only Archer-plus pressure, but no longer teleport-fast: real arrows, Deadeye spell ids, visible trails/impacts, disabling arrows, choking gas, `infiltrators_arrow`, non-teleport `alter_ego` sidestep, and a lighter phase-2 barrage. Water Wizard/Katara uses the `water_wizard` boss template with empty hands. It is flowing waterbender pressure: Water Wizard spell ids, natural running/strafe/recovery movement, water whip, splash, waterball, ice bind, capped springwater support, phase-2 hydro beam/avatar burst, visible water/ice particles, and no weapon, hover, or teleport. Frost Wizard/Elsa uses the `frost_wizard` boss template with empty hands. It is graceful grounded ice pressure: Wizards frost spell ids, frostbolt, frost shard, ice lance, frost nova, ice wall sweep, frost shield absorption, phase-2 blizzard hazard, shard storm, ice-step dodge, visible snow/ice particles, and no weapon, staff, hover, or teleport. Fire Wizard/Zuko uses the `fire_wizard` boss template with empty hands. It is fast firebender-style pressure: fire spell ids, aggressive running, flame rolls/steps, fire punches/projectiles, close flame sweeps, beam/hazard phase-2 fire pressure, and no weapon or teleport. Wind Wizard/Aang uses the `wind_wizard` boss template with empty hands. It is fast grounded airbender pressure: Wind Wizard spell ids, natural running/strafe/recovery movement, air cutters, gust cones, spiral gust, phase-2 updraft/avatar pressure, air roll/step dodges, visible wind particles, and no weapon, hover, or teleport. Forcemaster/Vi uses the `forcemaster` boss template with dual `forcemaster_rpg:unique_knuckle_1` / `unique_knuckle_0`. It is fast close-range boxer pressure: jab/cross, hook chain, straight punch, body breaker weakness, burstcrack, stonehand guard, phase-2 belial smashing and asal, visible Force Master punch/stone/rage VFX, and no sword, staff, ranged caster kit, hover, or teleport. Earth Wizard/Toph uses the `earth_wizard` boss template with empty hands. It is grounded earthbender-style pressure: Terra spell ids, active throw/side-cast/punch/groundsmash animations, stone throw/spear/impale/earthquake/ground ripple/drip circle/stone pillars/shattering stone/stone flesh, Force Master stonehand/burstcrack flavor, visible stone particles, normal sidestep dodge, and no weapon, floating, teleport, or melee weapon kit.

Tundra Archer/Traxex uses the `tundra_archer` boss template with `minecells:ice_bow` and bow fallback. It is ranged-only frost Archer-plus pressure: real arrows, `frozen_shot`, `enchanted_crystal_arrow`, `arctic_volley`, `frozen_pact` absorption, phase-2 `winters_grip` hazard and improved arctic volley, visible snow/frost particles, normal frost-step dodges, and no sword, melee kit, hover, or teleport. War Archer/Legolas uses the `war_archer` boss template with `archers:aether_longbow` and bow fallback. It is ranged-only battlefield Archer-plus pressure: real arrows, `dual_shot`, `pin_down`, `smoldering_arrow`, close anti-greed `point_blank_shot`, phase-2 `fan_of_fire` and improved point blank, visible crit/flame/sweep particles, normal roll/step movement, and no sword, melee kit, hover, or teleport.

Paladin/Tarnished uses the `paladin` boss template with `minecraft:mace` and `paladins:netherite_kite_shield` with vanilla shield fallback. It is final-boss Elden-style pressure: close guard counter, visible shield guard beats, shield bash, mace heavy, golden slam, holy shock, judgement, absorption-only golden barrier/battle banner, medium and back rolls, phase-2 holy beam, phase-2 Erdtree burst hazard, visible holy/shield particles, and no teleport or defensive waiting loop.

Forcemaster joins the current Cataclysm-phase-music boss set and uses a tighter 3-hit recovery cap like the faster pressure bosses.

Boss attacks rotate through a random fair-use bag during offense. Legal moves still respect range, phase, cooldown, line-of-sight cone, and weight, but a selected attack is suppressed until the current available attack pool has had a chance, and the last two attacks are avoided when alternatives exist.

When Jade is installed, hovering an NPC shows the NPC display name and the hovering player's friendship category for that NPC with a small heart, empty heart, or angry icon.

Gift limits are per NPC/player and reset by in-game schedule period. Finn defaults to one gift per in-game day, resetting at 05:00. Gifts adjust friendship only: neutral `+5`, liked `+25`, loved `+50`, disliked `-50`. Hitting an NPC changes friendship by `-10`; killing one changes friendship by `-300`; first daily chat changes friendship by `+25`.

NPC-to-player gifts start at friendship level 5 by default. Each NPC/player pair rolls one scheduled hour per in-game day from that NPC's non-sleep schedule hours. If the player is nearby when the hour is ready, the NPC creates a pending weighted random gift, shows a `@gift.png` balloon, and follows the player for `follow_seconds`. The item is only granted when the player right-clicks the NPC while a pending gift exists. Ignoring the chase does not delete the gift; the pending gift can be claimed later, including on a later in-game day, and the NPC can remind the player again once per day. Claiming opens the normal NPC dialog with a `THANKS` close button and sends a snackbar using the received item as icon. Gift LLM thinking and replies stay in that dialog, not in world-space balloons. Friendship level 9+ uses the rare weighted pool when available. LLM gift messages receive `{player}`, `{npc}`, `{item}`, and `{quantity}` in the prompt; fallback messages use the same placeholders.

NPC-to-NPC micro interactions run after gifting and daily greeting checks, before ambient life and normal scheduled movement. Authored dialogue requires a close player witness by default, so exchange content is not spent off-screen. When eligible NPCs meet within `npc_interactions.radius`, they can pause, walk toward each other, face each other, play authored `source_emote` / `target_emote` values when present, then fall back to `default_speaking_emote` for blank lines when configured, and hold the interaction open for `duration_seconds`; each nearby player receives each NPC's balloon once for the normal short balloon duration. First daily witnesses get a small 5-15 second nudge, while area cooldown, per-NPC daily budgets, per-NPC cooldowns, and same-pair cooldowns prevent rapid repeats. Each interaction records a global summary event for future LLM context.

Right-clicking either NPC during a micro interaction interrupts the exchange and opens a contextual dialog from the clicked NPC. Resident NPCs keep their normal dialog buttons; unhoused NPCs stay close-only. The clicked NPC references the partner, the just-shown lines, and the exchange topic; when LLM is enabled, the prompt asks for a short in-character reaction instead of a generic greeting. The same context remains available briefly after the balloons end, so a player can still ask what the NPCs were just discussing.

After higher-priority tasks and before routine schedule movement, NPCs can run ambient life actions. Ambient life is in-world-only SBL behavior: short movement, observation, or emote beats around Pokemon, nearby paths, home, town center, work blocks, or small scene objects. It does not open UI, ask LLMs, grant rewards, or change shop/training/workplace validity. During `work` hours with no assigned workplace, ambient life makes the NPC seek town/home/camp/Pokemon/object targets instead of standing in place, while the actual WORK flow and missing-workplace requirements remain unchanged. Authored `solo_moments` in `micro_interactions*.toml` can add rare short balloons and optional `emote` ids to those beats; entering close range during the active ambient event sends that player a fresh short balloon once. Sitting/lying catalog emotes are ambient posture only and cancel on talk, movement, damage, combat, or battle locks. Client-side balloon removal is duration-based after receipt, not distance-culling based. The Prism runtime config currently carries 250 ambient solo moments across `micro_interactions_ambient_01.toml` through `micro_interactions_ambient_06.toml`.

If a housed NPC dies, resident state marks it dead and the server announces the death through a global snackbar, world chat, and Discord death relay when enabled. The announcement includes the killer when the damage source is a player. Once the scheduled respawn day is due and the in-game hour is 05:00 or later, the server respawns it at its assigned bed if it has one. If an unhoused camper dies, the active camper remains reserved and respawns at the camping block instead. This is intentionally tolerant of debug time jumps that skip across the exact 05:00 scan window.

Home beds are validated against live bed blocks. If the assigned bed is broken or missing, the stored home is cleared, the NPC returns to the camping block when one is known, becomes the active camper again, and interaction can grant a new rent contract with lost-house dialog. Dead NPCs without a valid assigned bed camp-respawn instead of bed-respawning.

## Camper Rotation

- Only one active unhoused camper can exist at a camping block.
- Eligible campers are configured NPCs with `housing.can_move_in=true`, no valid home bed, and no live entity already present.
- The pool is unique by NPC id; once every configured NPC has a home, camp stops spawning new campers.
- Assigning a bed clears active camper state and schedules the next camper after a random `campers.cooldown_min_hours..cooldown_max_hours` Minecraft-hour delay.
- Breaking an assigned bed cancels that NPC's housed status and makes that NPC the active camper again, ahead of any new camper.
- Automatic cooldown spawning uses the stored camping point from `/ck camping set`.

## Commands

- `/npc reload`: reload NPC config files.
- `/ck camping set`: OP-only command that stores the camping point at the caller's position and tries to spawn the current eligible camper.
- `/ck town_center set <radius>`: OP-only command that stores the town center at the caller's position and sets meetup radius in blocks.
- `/npc spawn <id>`: spawn an NPC at the command source player's position.
- `/npc respawn status <id>`: show live/dead state, current day/hour, stored respawn day, home bed validity, and whether the NPC is ready to respawn.
- `/npc respawn <id>`: force-respawn a dead or missing NPC at its valid home bed.
- `/npc clear all confirm`: dangerous admin reset. Backs up NPC `state.json`, deletes all live NPC entities, clears all NPC world state, bed assignments, memories, camper state, and online rent contracts.
- `/npc clear <id> confirm`: dangerous admin reset for one NPC id. Backs up NPC `state.json`, deletes that live NPC entity, clears that NPC's state, bed assignment, memories, and online rent contracts for that NPC.
- `/npc friendship get <id> <player>`: show that player's points, level, and category for an NPC.
- `/npc friendship set <id> <player> <points>`: set points from `-1000` to `1000`.
- `/npc friendship add <id> <player> <delta>`: add or subtract points, clamped to `-1000..1000`.
- `/npc debug clock`: toggle live CK clock actionbar with `HH:MM AM/PM`, raw world time, tick-of-cycle, clock source, and daylight gamerule state.
- `/npc debug`: toggle realtime actionbar debug for the Chow Kingdom NPC under the player's crosshair. Run it on the same NPC again to stop. The actionbar includes current schedule time, tick-of-cycle, activity, task, navigation state, target, and debug time multiplier.
- `/npc debug llm`: show current NPC LLM enabled/provider/model settings plus the latest in-memory LLM failures, including HTTP status such as `429` or `403` when the provider returns one.
- `/npc debug emote`: toggle live admin chat traces for NPC emote attempts, including requested id, resolved id, surface, source, and play result.
- `/npc debug time <multiplier>`: speed up day time for debugging schedules. Use `1` to reset. Allowed range is `1` to `240`.
- `/npc debug balloon <id> <message>`: show a timed test balloon above the spawned NPC for nearby players.
- `/npc fight`: OP-only temporary boss duel with the Chow Kingdom NPC under the player's crosshair.
- `/npc fight <class_id>`: OP-only temporary boss duel that spawns a transient test NPC using the requested boss moveset id, such as `warrior`, `rogue`, `archer`, `bounty_hunter`, `wizard`, `water_wizard`, `frost_wizard`, `fire_wizard`, `wind_wizard`, `arcane_wizard`, `earth_wizard`, `priest`, `bard`, `forcemaster`, `berserker`, or `witcher`. The debug NPC keeps its generated boss id for cleanup but borrows the skin/body type from the first configured NPC with that class.
- `/npc animation debug`: toggle a Steve-textured Chow Kingdom NPC debug entity for the command player.
- `/npc animation custom_animation true|false`: toggle GeckoLib custom animation mode on the active debug Steve, or the NPC under the crosshair when no debug Steve is active.
- `/npc animation playerlike true|false`: toggle the Better Combat-compatible playerlike animation renderer on the active debug Steve, or the NPC under the crosshair when no debug Steve is active.
- `/npc animation body girl|boy <bust_size> [bounce] [floppy]`: set temporary Female Gender body values on the active debug Steve, or the NPC under the crosshair when no debug Steve is active. Example: `/npc animation body girl 0.8`.
- `/npc animation list`: list Gecko animation ids normally; if the active animation target has `playerlike = true`, list PlayerAnimator animation ids instead.
- `/npc animation idle`: enable custom animation mode and run the idle alias on the active debug Steve, or the NPC under the crosshair when no debug Steve is active.
- `/npc animation walk`: enable custom animation mode and run the walk alias on the active debug Steve, or the NPC under the crosshair when no debug Steve is active. The current weapon-socket reset file only defines `idle`, so this command is expected to fail until walk clips are re-authored.
- `/npc animation attack`: enable custom animation mode and run the attack alias on the active debug Steve, or the NPC under the crosshair when no debug Steve is active. The current weapon-socket reset file only defines `idle`, so this command is expected to fail until attack clips are re-authored.
- When `playerlike = true`, `/npc animations <animation>` queues any loaded PlayerAnimator id such as `bettercombat:one_handed_slash_horizontal_right`, `combat_roll:roll`, `simplyswords:some_clip`, or `spell_engine:some_clip`, with default aliases `attack`, `slash`, `dagger`, and `stab`. The canonical Better Combat namespace is `bettercombat`; `better_combat` is normalized as a debug alias.
- Playerlike command suggestions come from loaded `assets/<namespace>/player_animations/*.json` resources plus local dev scrape data at `build/playeranimator-clips/manifest.csv` when present. The command still accepts arbitrary `namespace:clip` ids even if a clip is not in the suggestions; the client log warns when PlayerAnimator has no matching loaded animation.
- Replaying the same one-shot debug animation forces a GeckoLib controller reset, so repeated `/npc animation attack` calls restart the attack clip instead of holding the previous finished pose.
- `/npc animation reload` or `/npc animations reload`: reload animation IDs from `playerlike.animation.json` and request a client resource reload. The resource reload sees files from the active client resource pack/classpath; in a dev run, source edits may still require Gradle resource processing if the client is serving `build/resources/main`.
- `/npc animation wear <item>` or `/npc animations wear <item>`: equip the active debug Steve or looked-at NPC with `hat`, `chestplate`, `leggings`, `boots`, `sword`, `left_sword`, `left <item>`, any item id such as `minecraft:iron_sword`, or `clear`. Held items render directly on authored Gecko hand-item socket bones using raw item model context plus one fixed item-model-space adapter. Vanilla armor layers render on the normal/playerlike NPC renderers; the current Gecko custom renderer does not draw vanilla armor layers yet.
- `/npc animation itemrot <x> <y> <z>`: add temporary synced raw item rotation offsets after the fixed held-item adapter. `/npc animation itemrot reset` clears the offsets.
- `/npc animation itempos <x> <y> <z>`: add temporary synced item position offsets in socket space. `/npc animation itempos reset` clears the position offsets.
- `/npc animation itemscale <scale>`: add a temporary synced uniform item scale multiplier. `/npc animation itemscale reset` returns to `1`.
- `/npc animation itemrotorder <order>`: change the debug offset application order. Accepted values are `xyz`, `xzy`, `yxz`, `yzx`, `zxy`, and `zyx`.
- `/npc animation itemrotspace socket|item`: choose whether debug offsets apply in socket space before the item adapter or item-local space after the adapter. Use `socket` when calibrating visible roll/pitch/yaw against the debug weapon bones.
- Gecko hand-item socket default rotation is `[0, 0, 0]`, meaning a held sword points forward and its crossguard is vertical.
- `/npc animation <animation>` or `/npc animations <animation>`: play any animation key from `playerlike.animation.json`; suggestions are refreshed from the JSON file.
- `/npc emote list`: show curated emote ids from `emotes.toml`, their backing PlayerAnimator/Emotecraft ids, allowed surfaces, and resolved/missing status.
- `/npc emote reload`: reload the emote catalog and playerlike animation ids.
- `/npc emote test <id>`: force-play a catalog emote on the active debug Steve or looked-at NPC.
- `/ck npc ...` and `/chowkingdom npc ...`: aliases.

## Jade

When Jade is installed, beds assigned through rent contracts show the owner, such as `Finn's Bed`.

## Extension Points

Add future runtime decision logic as SmartBrainLib behaviors in `NpcSmartBrain`. Keep static behavior knobs in `NpcJobDefinition` and schedules in `NpcScheduleDefinition`.

Add future data fields to `NpcDefinition` instead of hard-coding per-NPC behavior in the entity.

Add future store, gift, relationship, and quest systems by reading the stable NPC id and definition fields. The entity should stay thin: identity, saved positions, interaction entrypoint, short-lived talking state, debug state, and per-tick delegation.
