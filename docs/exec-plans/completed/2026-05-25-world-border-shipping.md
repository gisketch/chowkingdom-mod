# World Border Shipping Unlocks

## Goal

Add CKDM world border control for every server dimension. Default radius 1500. Expand radius from shipping-bin total chowcoins sold. Warn players near border through snackbar.

## Acceptance Criteria

- Fresh server applies radius 1500 to all dimensions.
- Threshold tiers map shipping total to radius: 0 -> 1500, 100000 -> 3000, 250000 -> 5000, 500000 -> 7500, 1000000 -> 10000.
- Uses `ShippingBinStore.totalChowcoinsSold()` and is checked where boss/tech shipping unlocks are checked.
- Border applies to every `ServerLevel`.
- Near-border players get snackbar warning with cooldown.
- Admin commands exist for reload/check/status/reset.
- Config lives under `config/gisketchs_chowkingdom_mod/world_border/settings.toml`.
- State lives in world data, not global config.
- Build/test pass.

## Context Links

- `src/main/kotlin/dev/gisketch/chowkingdom/shipping/ShippingBinFeature.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/shipping/ShippingBinStore.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/tech/TechLicenseFeature.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/bosses/BossEventsFeature.kt`
- `src/main/kotlin/dev/gisketch/chowkingdom/snackbar/`

## Steps

1. Add world border config.
2. Add world border persistent state.
3. Add feature runtime, commands, shipping checks, snackbar warning.
4. Register feature in mod init.
5. Update shipping check call sites.
6. Add docs.
7. Validate build/tests.

## Validation

- `./gradlew test`
- `./gradlew build`

## Decision Log

- No shrinking by default. Highest unlocked tier persists even if debug total is lowered.
- Thresholds are server economy total chowcoins sold, not item count.
- Admin reset clears highest tier and reapplies tier from current total.

## Progress Log

- 2026-05-25: Plan created.
- 2026-05-25: Implemented config/store/runtime/commands/shipping integrations/docs.
- 2026-05-25: Validated with `./gradlew test`, `./gradlew build`, and `./scripts/run-client-mac.sh --skip-build --no-launch`.
