# World Border

CKDM owns the server world border for every loaded dimension.

## Rules

- Config: `config/gisketchs_chowkingdom_mod/world_border/settings.toml`
- World state: `world/data/gisketchs_chowkingdom_mod/world_border/state.json`
- Source of progress: `ShippingBinStore.totalChowcoinsSold()`
- Default center: `0, 0`
- Default radius: `1,500` blocks

## Shipping Unlocks

| Total shipped Chowcoins | Radius |
| ---: | ---: |
| 0 | 1,500 |
| 100,000 | 3,000 |
| 250,000 | 5,000 |
| 500,000 | 7,500 |
| 1,000,000 | 10,000 |

The feature does not shrink borders by default. The highest unlocked threshold persists in world state. Admin reset clears that state and reapplies from the current shipping total.

## Player Warning

Players near the configured border edge get a cooldowned snackbar:

- title: `WORLD BORDER AHEAD`
- icon: `minecraft:barrier`
- default warning distance: 100 blocks
- default cooldown: 10 seconds per player

## Commands

```text
/ck worldborder status
/ck worldborder check
/ck worldborder reload
/ck worldborder reset
```

Aliases also exist under `/worldborder` and `/chowkingdom worldborder`.
